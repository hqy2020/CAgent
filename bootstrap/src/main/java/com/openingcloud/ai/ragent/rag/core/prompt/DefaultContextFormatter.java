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

package com.openingcloud.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.infra.token.TokenCounterService;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final MCPService mcpService;
    private final TokenCounterService tokenCounterService;

    @Override
    public String formatKbContext(List<NodeScore> kbIntents,
                                  Map<String, List<RetrievedChunk>> rerankedByIntent,
                                  int topK,
                                  int tokenBudget) {
        if (rerankedByIntent == null || rerankedByIntent.isEmpty()) {
            return "";
        }
        if (CollUtil.isEmpty(kbIntents)) {
            return formatChunksWithoutIntent(rerankedByIntent, topK, tokenBudget);
        }

        // 多意图场景：合并所有规则和文档
        if (kbIntents.size() > 1) {
            return formatMultiIntentContext(kbIntents, rerankedByIntent, topK, tokenBudget);
        }

        // 单意图场景：保持原有逻辑
        return formatSingleIntentContext(kbIntents.get(0), rerankedByIntent, topK, tokenBudget);
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore,
                                             Map<String, List<RetrievedChunk>> rerankedByIntent,
                                             int topK,
                                             int tokenBudget) {
        List<RetrievedChunk> chunks = rerankedByIntent.get(nodeScore.getNode().getId());
        if (CollUtil.isEmpty(chunks)) {
            return "";
        }
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        List<RetrievedChunk> limitedChunks = limitChunksByBudget(chunks, topK, tokenBudget, snippet);
        String body = limitedChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        StringBuilder block = new StringBuilder();
        if (StrUtil.isNotBlank(snippet)) {
            block.append("#### 回答规则\n").append(snippet).append("\n\n");
        }
        block.append("#### 知识库片段\n````text\n").append(body).append("\n````");
        return block.toString();
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents,
                                            Map<String, List<RetrievedChunk>> rerankedByIntent,
                                            int topK,
                                            int tokenBudget) {
        StringBuilder result = new StringBuilder();

        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        if (!snippets.isEmpty()) {
            result.append("#### 回答规则\n");
            for (int i = 0; i < snippets.size(); i++) {
                result.append(i + 1).append(". ").append(snippets.get(i)).append("\n");
            }
            result.append("\n");
        }

        // 2. 合并所有意图的文档片段（去重）
        List<RetrievedChunk> allChunks = rerankedByIntent.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();
        List<RetrievedChunk> limitedChunks = limitChunksByBudget(
                allChunks,
                topK,
                tokenBudget,
                snippets.isEmpty() ? "" : String.join("\n", snippets)
        );

        if (!limitedChunks.isEmpty()) {
            String body = limitedChunks.stream()
                    .map(RetrievedChunk::getText)
                    .collect(Collectors.joining("\n"));
            result.append("#### 知识库片段\n````text\n").append(body).append("\n````");
        }

        return result.toString();
    }

    private String formatChunksWithoutIntent(Map<String, List<RetrievedChunk>> rerankedByIntent,
                                             int topK,
                                             int tokenBudget) {
        int limit = topK > 0 ? topK : Integer.MAX_VALUE;
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (List<RetrievedChunk> list : rerankedByIntent.values()) {
            if (CollUtil.isEmpty(list)) {
                continue;
            }
            for (RetrievedChunk chunk : list) {
                chunks.add(chunk);
                if (chunks.size() >= limit) {
                    break;
                }
            }
            if (chunks.size() >= limit) {
                break;
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }

        List<RetrievedChunk> limitedChunks = limitChunksByBudget(chunks, limit, tokenBudget, "");
        String body = limitedChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        return "#### 知识库片段\n````text\n" + body + "\n````";
    }

    private List<RetrievedChunk> limitChunksByBudget(List<RetrievedChunk> chunks,
                                                     int topK,
                                                     int tokenBudget,
                                                     String snippet) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        int chunkLimit = topK > 0 ? topK : Integer.MAX_VALUE;
        int availableTokens = tokenBudget >= 0 ? Math.max(0, tokenBudget - countTokens(snippet)) : Integer.MAX_VALUE;
        List<RetrievedChunk> limited = new ArrayList<>();
        int usedTokens = 0;
        for (RetrievedChunk chunk : chunks) {
            if (limited.size() >= chunkLimit) {
                break;
            }
            int chunkTokens = countTokens(chunk == null ? null : chunk.getText());
            if (!limited.isEmpty() && usedTokens + chunkTokens > availableTokens) {
                continue;
            }
            limited.add(chunk);
            usedTokens += chunkTokens;
            if (usedTokens >= availableTokens) {
                break;
            }
        }
        if (limited.isEmpty()) {
            return List.of(chunks.get(0));
        }
        return limited;
    }

    private int countTokens(String content) {
        Integer tokens = tokenCounterService.countTokens(content);
        return tokens == null ? 0 : Math.max(tokens, 0);
    }

    @Override
    public String formatMcpContext(List<MCPResponse> responses, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(responses)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mcpService.mergeResponsesToText(responses);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        Map<String, List<MCPResponse>> grouped = responses.stream()
                .filter(r -> StrUtil.isNotBlank(r.getToolId()))
                .collect(Collectors.groupingBy(MCPResponse::getToolId));

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<MCPResponse> toolResponses = grouped.get(entry.getKey());
                    if (CollUtil.isEmpty(toolResponses)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mcpService.mergeResponsesToText(toolResponses);
                    if (StrUtil.isBlank(body)) {
                        return "";
                    }
                    StringBuilder block = new StringBuilder();
                    if (StrUtil.isNotBlank(snippet)) {
                        block.append("#### 意图规则\n").append(snippet).append("\n");
                    }
                    block.append("#### 动态数据片段\n").append(body);

                    List<String> statuses = buildStatusLines(toolResponses);
                    if (!statuses.isEmpty()) {
                        block.append("\n#### 执行状态\n");
                        statuses.forEach(line -> block.append("- ").append(line).append("\n"));
                    }

                    List<String> references = extractReferences(toolResponses);
                    if (!references.isEmpty()) {
                        block.append("#### 引用来源\n");
                        references.forEach(line -> block.append(line).append("\n"));
                    }
                    return block.toString();
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    private List<String> buildStatusLines(List<MCPResponse> toolResponses) {
        List<String> lines = new ArrayList<>();
        boolean hasFallback = toolResponses.stream().anyMatch(MCPResponse::isFallbackUsed);
        if (hasFallback) {
            lines.add("工具发生降级，以下结果包含 fallback 输出。");
        }

        List<String> failures = toolResponses.stream()
                .filter(response -> !response.isSuccess())
                .map(response -> {
                    String code = StrUtil.blankToDefault(response.getErrorCode(), "UNKNOWN");
                    String message = StrUtil.blankToDefault(response.getErrorMessage(), "未知错误");
                    return code + ": " + message;
                })
                .distinct()
                .toList();
        lines.addAll(failures);
        return lines;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractReferences(List<MCPResponse> toolResponses) {
        List<String> references = new ArrayList<>();
        for (MCPResponse response : toolResponses) {
            if (response == null || response.getData() == null) {
                continue;
            }
            Object items = response.getData().get("items");
            if (!(items instanceof List<?> itemList)) {
                continue;
            }
            for (Object item : itemList) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String title = StrUtil.blankToDefault(stringValue(map.get("title")), "未命名来源");
                String url = stringValue(map.get("url"));
                String source = stringValue(map.get("source"));
                String date = stringValue(map.get("date"));
                StringBuilder line = new StringBuilder("- ").append(title);
                if (StrUtil.isNotBlank(source)) {
                    line.append(" | ").append(source);
                }
                if (StrUtil.isNotBlank(date)) {
                    line.append(" | ").append(date);
                }
                if (StrUtil.isNotBlank(url)) {
                    line.append(" | ").append(url);
                }
                references.add(line.toString());
            }
        }
        return references.stream().distinct().toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
