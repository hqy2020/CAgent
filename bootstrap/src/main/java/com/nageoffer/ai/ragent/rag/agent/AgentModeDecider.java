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

package com.nageoffer.ai.ragent.rag.agent;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent 模式判定器
 */
@Component
@RequiredArgsConstructor
public class AgentModeDecider {

    private static final Pattern MULTI_STEP_HINT = Pattern.compile(
            "(整理|总结|汇总|计划|先.*再|然后|并且|步骤|写入|落库|多源|multi-step)",
            Pattern.CASE_INSENSITIVE
    );

    private final RAGConfigProperties ragConfigProperties;

    @RagTraceNode(name = "agent-decide", type = "AGENT_DECIDE")
    public AgentModeDecision decide(String question,
                                    List<SubQuestionIntent> subIntents,
                                    RetrievalContext firstRoundContext) {
        if (!Boolean.TRUE.equals(ragConfigProperties.getAgentEnabled())) {
            return AgentModeDecision.disabled("agent disabled");
        }

        if (subIntents == null || subIntents.isEmpty()) {
            return AgentModeDecision.disabled("no intents");
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

        double bestScore = resolveBestScore(firstRoundContext);
        if (bestScore < ragConfigProperties.getAgentLowConfidenceThreshold()) {
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

