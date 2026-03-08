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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequestSource;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    /**
     * MCP 意图最低置信度阈值（高于通用意图阈值），用于抑制误触发工具调用。
     */
    private static final double MCP_INTENT_MIN_SCORE = 0.60D;
    private static final Pattern WEB_NEWS_QUERY_HINT = Pattern.compile(
            "(联网|上网|互联网|web|internet|google|bing|百度|新闻|热搜|实时|最新|快讯|简报|热点)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OBSIDIAN_QUERY_HINT = Pattern.compile(
            "(笔记|obsidian|日记|markdown|README|readme|目录|文件夹|路径|path|vault|\\.md\\b)",
            Pattern.CASE_INSENSITIVE
    );

    private final ContextFormatter contextFormatter;
    private final MCPService mcpService;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor ragContextExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     *
     * @param subIntents 子问题意图列表，包含每个子问题及其相关的意图节点和评分
     * @param topK       需要返回的最相关结果数量，若 ≤0 则使用默认值
     * @return RetrievalContext 检索上下文，包含知识库上下文、MCP上下文和分组的检索块
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        return retrieve(subIntents, topK, CancellationToken.NONE);
    }

    /**
     * 检索方法（支持取消令牌）
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK, CancellationToken token) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .mcpContext("")
                    .kbContext("")
                    .intentChunks(Map.of())
                    .build();
        }

        token.throwIfCancelled();

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> {
                            token.throwIfCancelled();
                            return buildSubQuestionContext(
                                    si,
                                    resolveSubQuestionTopK(si, finalTopK),
                                    token
                            );
                        },
                        ragContextExecutor
                ))
                .toList();

        List<SubQuestionContext> contexts = new java.util.ArrayList<>(tasks.size());
        for (CompletableFuture<SubQuestionContext> task : tasks) {
            token.throwIfCancelled();
            try {
                contexts.add(task.join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof TaskCancelledException) {
                    throw (TaskCancelledException) e.getCause();
                }
                throw e;
            }
        }

        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();

        for (SubQuestionContext context : contexts) {
            if (StrUtil.isNotBlank(context.kbContext())) {
                appendSection(kbBuilder, context.question(), context.kbContext());
            }
            if (StrUtil.isNotBlank(context.mcpContext())) {
                appendSection(mcpBuilder, context.question(), context.mcpContext());
            }
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, int topK, CancellationToken token) {
        List<NodeScore> kbIntents = filterKbIntents(intent.nodeScores());
        List<NodeScore> mcpIntents = filterMCPIntents(intent.subQuestion(), intent.nodeScores());

        // 仅在“强 MCP-only”时跳过 KB 检索；其余场景（含无意图）都允许 KB 通道兜底。
        KbResult kbResult = shouldRetrieveKb(kbIntents, mcpIntents)
                ? retrieveAndRerank(intent, kbIntents, topK, token)
                : KbResult.empty();

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents, token)
                : "";

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 子问题实际 TopK 计算规则：
     * 1. 命中 KB 意图节点且配置了节点级 topK：取最大值（多意图保守放大）
     * 2. 没有任何可用节点级 topK：回退到全局 topK
     */
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return filterKbIntents(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    private void appendSection(StringBuilder builder, String question, String context) {
        builder.append("---\n")
                .append("**子问题**：").append(question).append("\n\n")
                .append("**相关文档**：\n")
                .append(context).append("\n\n");
    }

    private List<NodeScore> filterMCPIntents(String question, List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= MCP_INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .filter(ns -> isReadOnlyTool(ns.getNode().getMcpToolId()))
                .filter(ns -> isQuestionCompatibleWithTool(question, ns.getNode().getMcpToolId()))
                .toList();
    }

    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;
                    }
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents, CancellationToken token) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        List<NodeScore> deduplicatedIntents = deduplicateMcpIntentsByTool(mcpIntents);
        if (CollUtil.isEmpty(deduplicatedIntents)) {
            return "";
        }
        List<MCPResponse> responses = executeMcpTools(question, deduplicatedIntents, token);
        if (responses.isEmpty()) {
            return "";
        }
        return contextFormatter.formatMcpContext(responses, deduplicatedIntents);
    }

    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK,
                                        CancellationToken token) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK, token);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文）
        Map<String, List<RetrievedChunk>> intentChunks = new ConcurrentHashMap<>();

        // 如果有意图识别结果，按意图节点 ID 分组
        if (CollUtil.isNotEmpty(kbIntents)) {
            // 将所有 chunks 按意图节点 ID 分配
            // 注意：多通道检索返回的 chunks 无法精确对应到某个意图节点
            // 所以我们将所有 chunks 分配给每个意图节点
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            // 如果没有意图识别结果，使用特殊 key
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
    }

    private boolean shouldRetrieveKb(List<NodeScore> kbIntents, List<NodeScore> mcpIntents) {
        if (CollUtil.isNotEmpty(kbIntents)) {
            return true;
        }
        return CollUtil.isEmpty(mcpIntents);
    }

    private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores,
                                                CancellationToken token) {
        List<MCPRequest> requests = mcpIntentScores.stream()
                .map(ns -> buildMcpRequest(question, ns.getNode()))
                .filter(Objects::nonNull)
                .toList();

        return requests.isEmpty() ? List.of() : mcpService.executeBatch(requests, token);
    }

    private List<NodeScore> deduplicateMcpIntentsByTool(List<NodeScore> mcpIntents) {
        Map<String, NodeScore> byToolId = new LinkedHashMap<>();
        for (NodeScore nodeScore : mcpIntents) {
            if (nodeScore == null || nodeScore.getNode() == null) {
                continue;
            }
            String toolId = nodeScore.getNode().getMcpToolId();
            if (StrUtil.isBlank(toolId)) {
                continue;
            }
            NodeScore existing = byToolId.get(toolId);
            if (existing == null || nodeScore.getScore() > existing.getScore()) {
                byToolId.put(toolId, nodeScore);
            }
        }
        return byToolId.values().stream().toList();
    }

    private MCPRequest buildMcpRequest(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        MCPTool tool = executorOpt.get().getToolDefinition();
        if (tool.getOperationType() == MCPTool.OperationType.WRITE) {
            log.info("检索阶段跳过写工具, toolId: {}", toolId);
            return null;
        }

        String customParamPrompt = intentNode.getParamPromptTemplate();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);
        MCPParameterExtractor.ParameterValidationResult validation = mcpParameterExtractor.validate(new LinkedHashMap<>(params), tool);
        if (validation != null && !validation.valid()) {
            log.info("MCP 参数校验未通过，跳过工具执行并回退知识库, toolId: {}, missingParams: {}",
                    toolId, validation.missingParams());
            return null;
        }

        return MCPRequest.builder()
                .toolId(toolId)
                .userQuestion(question)
                .requestSource(MCPRequestSource.RETRIEVAL)
                .parameters(params)
                .build();
    }

    private boolean isReadOnlyTool(String toolId) {
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        return executorOpt.map(MCPToolExecutor::getToolDefinition)
                .map(MCPTool::getOperationType)
                .orElse(MCPTool.OperationType.READ) == MCPTool.OperationType.READ;
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }

    private boolean isQuestionCompatibleWithTool(String question, String toolId) {
        if (StrUtil.isBlank(toolId) || StrUtil.isBlank(question)) {
            return true;
        }
        String normalized = StrUtil.trim(question);
        return switch (toolId) {
            case "web_news_search" -> WEB_NEWS_QUERY_HINT.matcher(normalized).find();
            case "obsidian_read", "obsidian_search", "obsidian_list" -> OBSIDIAN_QUERY_HINT.matcher(normalized).find();
            default -> true;
        };
    }
}
