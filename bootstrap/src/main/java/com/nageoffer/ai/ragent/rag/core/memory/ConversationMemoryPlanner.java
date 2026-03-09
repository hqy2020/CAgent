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

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * 基于预算对会话记忆进行裁剪和分配。
 */
@Service
public class ConversationMemoryPlanner {

    static final int MIN_RECENT_TURNS = 2;
    static final int SYSTEM_PROMPT_RESERVE_TOKENS = 1000;
    static final int MIN_RETRIEVAL_TOP_K = 3;

    private final MemoryProperties memoryProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final TokenCounterService tokenCounterService;

    public ConversationMemoryPlanner(MemoryProperties memoryProperties,
                                     RAGConfigProperties ragConfigProperties,
                                     TokenCounterService tokenCounterService) {
        this.memoryProperties = memoryProperties;
        this.ragConfigProperties = ragConfigProperties;
        this.tokenCounterService = tokenCounterService;
    }

    public ConversationMemoryPlan plan(ConversationMemorySnapshot snapshot, String question) {
        ConversationMemorySnapshot safeSnapshot = snapshot == null ? ConversationMemorySnapshot.empty() : snapshot;

        PlannedHistory answerHistory = buildHistory(
                safeSnapshot.getSummary(),
                safeSnapshot.getRecentHistory(),
                memoryProperties.getHistoryBudgetTokens()
        );
        PlannedHistory rewriteHistory = buildHistory(
                safeSnapshot.getSummary(),
                safeSnapshot.getRecentHistory(),
                ragConfigProperties.getQueryRewriteMaxHistoryTokens()
        );

        int questionTokens = countTokens(question);
        int configuredRetrievalBudget = positiveOrZero(memoryProperties.getRetrievalBudgetTokens());
        int availableInputBudget = positiveOrZero(memoryProperties.getInputBudgetTokens())
                - SYSTEM_PROMPT_RESERVE_TOKENS
                - questionTokens
                - answerHistory.tokens();
        int effectiveRetrievalBudget = Math.min(configuredRetrievalBudget, Math.max(0, availableInputBudget));
        int retrievalTopK = resolveRetrievalTopK(effectiveRetrievalBudget, configuredRetrievalBudget);

        return ConversationMemoryPlan.builder()
                .rewriteHistory(rewriteHistory.messages())
                .answerHistory(answerHistory.messages())
                .historyTokens(answerHistory.tokens())
                .summaryTokens(answerHistory.summaryIncluded() ? countTokens(answerHistory.summaryContent()) : 0)
                .recentTurnsKept(answerHistory.recentTurnsKept())
                .summaryIncluded(answerHistory.summaryIncluded())
                .retrievalTopK(retrievalTopK)
                .retrievalBudgetTokens(effectiveRetrievalBudget)
                .rewriteHistoryTokens(rewriteHistory.tokens())
                .rewriteSummaryIncluded(rewriteHistory.summaryIncluded())
                .build();
    }

    private PlannedHistory buildHistory(ChatMessage summary, List<ChatMessage> recentHistory, Integer budgetTokens) {
        List<List<ChatMessage>> turns = splitTurns(recentHistory);
        int targetBudget = positiveOrZero(budgetTokens);
        int mandatoryTurns = Math.min(MIN_RECENT_TURNS, turns.size());

        List<Integer> selectedIndexes = new ArrayList<>();
        int tokens = 0;
        for (int i = turns.size() - mandatoryTurns; i < turns.size(); i++) {
            if (i < 0) {
                continue;
            }
            selectedIndexes.add(i);
            tokens += countTokens(turns.get(i));
        }

        int summaryTokens = countTokens(summary == null ? null : summary.getContent());
        boolean includeSummary = false;
        if (summary != null && StrUtil.isNotBlank(summary.getContent())) {
            if (turns.isEmpty()) {
                includeSummary = true;
            } else if (tokens + summaryTokens <= targetBudget) {
                includeSummary = true;
                tokens += summaryTokens;
            }
        }

        for (int i = turns.size() - mandatoryTurns - 1; i >= 0; i--) {
            int turnTokens = countTokens(turns.get(i));
            if (tokens + turnTokens > targetBudget) {
                continue;
            }
            selectedIndexes.add(i);
            tokens += turnTokens;
        }

        Collections.sort(selectedIndexes);
        List<ChatMessage> messages = new ArrayList<>();
        if (includeSummary) {
            messages.add(summary);
        }
        for (Integer index : selectedIndexes) {
            messages.addAll(turns.get(index));
        }

        if (messages.isEmpty() && summary != null && StrUtil.isNotBlank(summary.getContent())) {
            messages = List.of(summary);
            includeSummary = true;
            tokens = summaryTokens;
        }

        return new PlannedHistory(messages, tokens, includeSummary, selectedIndexes.size(),
                includeSummary ? summary.getContent() : null);
    }

    private List<List<ChatMessage>> splitTurns(List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return List.of();
        }
        List<List<ChatMessage>> turns = new ArrayList<>();
        List<ChatMessage> currentTurn = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message == null || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if (message.getRole() == ChatMessage.Role.SYSTEM) {
                continue;
            }
            if (message.getRole() == ChatMessage.Role.USER && !currentTurn.isEmpty()) {
                turns.add(List.copyOf(currentTurn));
                currentTurn = new ArrayList<>();
            }
            currentTurn.add(message);
        }
        if (!currentTurn.isEmpty()) {
            turns.add(List.copyOf(currentTurn));
        }
        return turns;
    }

    private int resolveRetrievalTopK(int effectiveRetrievalBudget, int configuredRetrievalBudget) {
        if (configuredRetrievalBudget <= 0) {
            return MIN_RETRIEVAL_TOP_K;
        }
        long scaled = Math.round((double) DEFAULT_TOP_K * effectiveRetrievalBudget / configuredRetrievalBudget);
        int candidate = (int) scaled;
        return Math.max(MIN_RETRIEVAL_TOP_K, Math.min(DEFAULT_TOP_K, candidate));
    }

    private int countTokens(String content) {
        Integer tokens = tokenCounterService.countTokens(content);
        return tokens == null ? 0 : Math.max(tokens, 0);
    }

    private int countTokens(List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return 0;
        }
        return messages.stream()
                .filter(message -> message != null && StrUtil.isNotBlank(message.getContent()))
                .mapToInt(message -> countTokens(message.getContent()))
                .sum();
    }

    private int positiveOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private record PlannedHistory(List<ChatMessage> messages,
                                  int tokens,
                                  boolean summaryIncluded,
                                  int recentTurnsKept,
                                  String summaryContent) {
    }
}
