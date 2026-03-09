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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final TokenCounterService tokenCounterService;
    private final RagTraceRecordService ragTraceRecordService;

    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
    }

    /**
     * 先用默认改写做归一化，再进行多问句拆分。
     */
    private RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, List.of());

        // 兜底：使用归一化结果 + 规则拆分
    }

    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {
        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);

        try {
            String raw = llmService.chat(req);
            RewriteResult parsed = parseRewriteAndSplit(raw);

            if (parsed != null) {
                log.info("""
                        RAG用户问题查询改写+拆分：
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }

            log.warn("查询改写+拆分解析失败，使用归一化问题兜底 - normalizedQuestion={}", normalizedQuestion);
        } catch (Exception e) {
            log.warn("查询改写+拆分 LLM 调用失败，使用归一化问题兜底 - question={}，normalizedQuestion={}", originalQuestion, normalizedQuestion, e);
        }

        // 统一兜底逻辑
        return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
    }

    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 只保留最近几轮的 User 和 Assistant 消息，过滤掉 System 摘要避免 Token 浪费
        if (CollUtil.isNotEmpty(history)) {
            int maxMessages = ragConfigProperties.getQueryRewriteMaxHistoryMessages();
            int maxTokens = ragConfigProperties.getQueryRewriteMaxHistoryTokens();
            int maxChars = ragConfigProperties.getQueryRewriteMaxHistoryChars();

            List<ChatMessage> filtered = history.stream()
                    .filter(this::isRewriteHistoryMessage)
                    .toList();
            List<ChatMessage> recentHistory = trimRewriteHistory(filtered, maxMessages, maxTokens, maxChars);

            messages.addAll(recentHistory);
            recordRewriteTrace(recentHistory);
        } else {
            recordRewriteTrace(List.of());
        }

        messages.add(ChatMessage.user(question));

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }

    private List<ChatMessage> trimRewriteHistory(List<ChatMessage> history,
                                                 int maxMessages,
                                                 int maxTokens,
                                                 int maxChars) {
        if (CollUtil.isEmpty(history)) {
            return List.of();
        }
        List<ChatMessage> mutable = new ArrayList<>(history);
        ChatMessage summary = extractSummary(mutable);

        if (maxMessages > 0) {
            shrinkToLimit(mutable, maxMessages, this::messageCount, summary);
        }
        if (maxTokens > 0) {
            shrinkToLimit(mutable, maxTokens, this::tokenCount, summary);
        }
        if (maxChars > 0) {
            shrinkToLimit(mutable, maxChars, this::charCount, summary);
        }
        return List.copyOf(mutable);
    }

    private void shrinkToLimit(List<ChatMessage> messages,
                               int limit,
                               java.util.function.ToIntFunction<List<ChatMessage>> metric,
                               ChatMessage summary) {
        if (limit <= 0 || CollUtil.isEmpty(messages)) {
            return;
        }
        while (messages.size() > 1 && metric.applyAsInt(messages) > limit) {
            if (summary != null && messages.contains(summary)) {
                messages.remove(summary);
                summary = null;
                continue;
            }
            messages.remove(0);
        }
    }

    private int messageCount(List<ChatMessage> messages) {
        return messages.size();
    }

    private int tokenCount(List<ChatMessage> messages) {
        return messages.stream()
                .filter(this::isRewriteHistoryMessage)
                .mapToInt(message -> {
                    Integer tokens = tokenCounterService.countTokens(message.getContent());
                    return tokens == null ? 0 : Math.max(tokens, 0);
                })
                .sum();
    }

    private int charCount(List<ChatMessage> messages) {
        return messages.stream()
                .filter(this::isRewriteHistoryMessage)
                .map(ChatMessage::getContent)
                .mapToInt(StrUtil::length)
                .sum();
    }

    private ChatMessage extractSummary(List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return null;
        }
        ChatMessage first = history.get(0);
        if (first != null && first.getRole() == ChatMessage.Role.SYSTEM && StrUtil.isNotBlank(first.getContent())) {
            return first;
        }
        return null;
    }

    private boolean isRewriteHistoryMessage(ChatMessage msg) {
        if (msg == null || StrUtil.isBlank(msg.getContent())) {
            return false;
        }
        if (msg.getRole() == ChatMessage.Role.USER || msg.getRole() == ChatMessage.Role.ASSISTANT) {
            return true;
        }
        return msg.getRole() == ChatMessage.Role.SYSTEM
                && (msg.getContent().startsWith("对话摘要：") || msg.getContent().startsWith("摘要："));
    }

    private void recordRewriteTrace(List<ChatMessage> history) {
        String traceId = RagTraceContext.getTraceId();
        String nodeId = RagTraceContext.currentNodeId();
        if (StrUtil.isBlank(traceId) || StrUtil.isBlank(nodeId)) {
            return;
        }
        ragTraceRecordService.updateNodeExtraData(traceId, nodeId, Map.of(
                "rewriteHistoryTokens", tokenCount(history),
                "rewriteHistoryMessages", history.size(),
                "rewriteSummaryIncluded", extractSummary(history) != null
        ));
    }


    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }

            // 尊重 LLM 的 should_split 决策
            boolean shouldSplit = obj.has("should_split")
                    && obj.get("should_split").getAsBoolean();
            if (!shouldSplit) {
                return new RewriteResult(rewrite, List.of(rewrite));
            }

            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            ".*(什么|怎么|如何|为什么|为何|哪|吗|呢|几|多少|是否|能否|可否|有没有|是不是).*");

    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        return parts.stream()
                .map(s -> {
                    if (QUESTION_PATTERN.matcher(s).matches()) {
                        return s + "？";
                    }
                    return s;
                })
                .toList();
    }
}
