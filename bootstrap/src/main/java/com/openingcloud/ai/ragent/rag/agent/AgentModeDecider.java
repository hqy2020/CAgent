/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.openingcloud.ai.ragent.rag.agent;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.framework.trace.RagTraceNode;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.DefaultMCPToolRegistry;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.openingcloud.ai.ragent.rag.util.NoteWriteIntentHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent 模式判定器
 */
@Component
public class AgentModeDecider {

    private static final Pattern MULTI_STEP_HINT = Pattern.compile(
            "(整理|总结|汇总|计划|先.*再|然后|并且|步骤|写入|落库|多源|multi-step)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DATE_TIME_LOOKUP_HINT = Pattern.compile(
            "(今天几号|今天几月几号|今天几月几日|今天星期几|今天周几|现在几点|当前时间|当前日期|现在日期|几号|几月几号|几月几日|星期几|周几|日期|时间|date|time|day)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WEB_SEARCH_LOOKUP_HINT = Pattern.compile(
            "(联网|上网|互联网|web|internet|google|bing|百度|新闻|热搜|实时|最新|天气|股价|汇率)",
            Pattern.CASE_INSENSITIVE
    );

    private final RAGConfigProperties ragConfigProperties;
    private final MCPToolRegistry mcpToolRegistry;

    @Autowired
    public AgentModeDecider(RAGConfigProperties ragConfigProperties, MCPToolRegistry mcpToolRegistry) {
        this.ragConfigProperties = ragConfigProperties;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    public AgentModeDecider(RAGConfigProperties ragConfigProperties) {
        this(ragConfigProperties, new DefaultMCPToolRegistry(List.of()));
    }

    @RagTraceNode(name = "agent-decide", type = "AGENT_DECIDE")
    public AgentModeDecision decide(String question,
                                    List<SubQuestionIntent> subIntents,
                                    RetrievalContext firstRoundContext) {
        if (!Boolean.TRUE.equals(ragConfigProperties.getAgentEnabled())) {
            return AgentModeDecision.disabled("agent disabled");
        }

        if (isDateTimeLookupQuestion(question)) {
            return AgentModeDecision.disabled("datetime lookup question");
        }
        if (isWebSearchLookupQuestion(question)) {
            return AgentModeDecision.disabled("web search lookup question");
        }

        if (isLikelyNoteWriteQuestion(question)) {
            return AgentModeDecision.enabled("note write language hint", resolveBestScore(firstRoundContext));
        }

        if (subIntents == null || subIntents.isEmpty()) {
            return AgentModeDecision.disabled("no intents");
        }

        if (containsWriteMcpIntent(subIntents)) {
            return AgentModeDecision.enabled("write mcp intent", resolveBestScore(firstRoundContext));
        }

        if (subIntents.size() >= 2) {
            return AgentModeDecision.enabled("multiple sub-questions", resolveBestScore(firstRoundContext));
        }

        if (containsKbAndMcp(subIntents)) {
            return AgentModeDecision.enabled("mixed kb + mcp intents", resolveBestScore(firstRoundContext));
        }

        if (isMultiStepText(question)) {
            return AgentModeDecision.enabled("multi-step language hint", resolveBestScore(firstRoundContext));
        }

        if (isReadOnlyMcpRequest(subIntents)) {
            return AgentModeDecision.disabled("read-only mcp request");
        }

        double bestScore = resolveBestScore(firstRoundContext);
        if (containsKbIntent(subIntents) && bestScore < ragConfigProperties.getAgentLowConfidenceThreshold()) {
            return AgentModeDecision.enabled("low first-round retrieval confidence", bestScore);
        }

        return AgentModeDecision.disabled("simple request");
    }

    private boolean containsKbAndMcp(List<SubQuestionIntent> subIntents) {
        boolean hasKb = false;
        boolean hasMcp = false;
        for (SubQuestionIntent intent : subIntents) {
            List<NodeScore> scores = intent.nodeScores();
            if (scores == null) {
                continue;
            }
            for (NodeScore nodeScore : scores) {
                if (nodeScore == null || nodeScore.getNode() == null) {
                    continue;
                }
                IntentKind kind = nodeScore.getNode().getKind();
                if (kind == IntentKind.MCP) {
                    hasMcp = true;
                } else if (kind == null || kind == IntentKind.KB) {
                    hasKb = true;
                }
                if (hasKb && hasMcp) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isMultiStepText(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        return MULTI_STEP_HINT.matcher(question).find();
    }

    private boolean isDateTimeLookupQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = StrUtil.trim(question);
        boolean dateTimeLookup = DATE_TIME_LOOKUP_HINT.matcher(normalized).find();
        if (!dateTimeLookup) {
            return false;
        }
        return !NoteWriteIntentHelper.isLikelyNoteWriteQuestion(normalized);
    }

    private boolean isWebSearchLookupQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = StrUtil.trim(question);
        boolean webSearchLookup = WEB_SEARCH_LOOKUP_HINT.matcher(normalized).find();
        if (!webSearchLookup) {
            return false;
        }
        return !NoteWriteIntentHelper.isLikelyNoteWriteQuestion(normalized);
    }

    private boolean containsWriteMcpIntent(List<SubQuestionIntent> subIntents) {
        for (SubQuestionIntent intent : subIntents) {
            List<NodeScore> scores = intent.nodeScores();
            if (scores == null) {
                continue;
            }
            for (NodeScore nodeScore : scores) {
                if (nodeScore == null || nodeScore.getNode() == null) {
                    continue;
                }
                if (nodeScore.getNode().getKind() != IntentKind.MCP) {
                    continue;
                }
                String toolId = nodeScore.getNode().getMcpToolId();
                if (toolId != null && isWriteTool(toolId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLikelyNoteWriteQuestion(String question) {
        return NoteWriteIntentHelper.isLikelyNoteWriteQuestion(question);
    }

    private boolean isReadOnlyMcpRequest(List<SubQuestionIntent> subIntents) {
        boolean hasMcp = false;
        boolean hasKb = false;
        for (SubQuestionIntent intent : subIntents) {
            List<NodeScore> scores = intent.nodeScores();
            if (scores == null) {
                continue;
            }
            for (NodeScore nodeScore : scores) {
                if (nodeScore == null || nodeScore.getNode() == null) {
                    continue;
                }
                IntentKind kind = nodeScore.getNode().getKind();
                if (kind == IntentKind.MCP) {
                    hasMcp = true;
                    String toolId = nodeScore.getNode().getMcpToolId();
                    if (toolId != null && isWriteTool(toolId)) {
                        return false;
                    }
                    continue;
                }
                if (kind == null || kind == IntentKind.KB) {
                    hasKb = true;
                }
            }
        }
        return hasMcp && !hasKb;
    }

    private boolean isWriteTool(String toolId) {
        return mcpToolRegistry.getExecutor(toolId)
                .map(MCPToolExecutor::getToolDefinition)
                .map(tool -> tool.getOperationType() == MCPTool.OperationType.WRITE || isKnownWriteToolId(toolId))
                .orElseGet(() -> isKnownWriteToolId(toolId));
    }

    private boolean isKnownWriteToolId(String toolId) {
        return switch (toolId == null ? "" : toolId.trim()) {
            case "obsidian_create", "obsidian_update", "obsidian_replace", "obsidian_delete", "obsidian_video_transcript" -> true;
            default -> false;
        };
    }

    private boolean containsKbIntent(List<SubQuestionIntent> subIntents) {
        for (SubQuestionIntent intent : subIntents) {
            List<NodeScore> scores = intent.nodeScores();
            if (scores == null) {
                continue;
            }
            for (NodeScore nodeScore : scores) {
                if (nodeScore == null || nodeScore.getNode() == null) {
                    continue;
                }
                IntentKind kind = nodeScore.getNode().getKind();
                if (kind == null || kind == IntentKind.KB) {
                    return true;
                }
            }
        }
        return false;
    }

    private double resolveBestScore(RetrievalContext context) {
        if (context == null || context.getIntentChunks() == null || context.getIntentChunks().isEmpty()) {
            return 0.0D;
        }
        double best = 0D;
        for (Map.Entry<String, List<RetrievedChunk>> entry : context.getIntentChunks().entrySet()) {
            List<RetrievedChunk> chunks = entry.getValue();
            if (chunks == null) {
                continue;
            }
            for (RetrievedChunk chunk : chunks) {
                if (chunk != null && chunk.getScore() != null) {
                    best = Math.max(best, chunk.getScore());
                }
            }
        }
        return best;
    }
}
