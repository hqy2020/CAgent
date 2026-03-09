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

package com.nageoffer.ai.ragent.rag.memory;

import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryPlan;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryPlanner;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemorySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryPlannerTests {

    @Mock
    private MemoryProperties memoryProperties;
    @Mock
    private RAGConfigProperties ragConfigProperties;
    @Mock
    private TokenCounterService tokenCounterService;

    private ConversationMemoryPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ConversationMemoryPlanner(memoryProperties, ragConfigProperties, tokenCounterService);
        lenient().when(memoryProperties.getInputBudgetTokens()).thenReturn(8000);
        lenient().when(memoryProperties.getHistoryBudgetTokens()).thenReturn(50);
        lenient().when(memoryProperties.getRetrievalBudgetTokens()).thenReturn(5000);
        lenient().when(ragConfigProperties.getQueryRewriteMaxHistoryTokens()).thenReturn(50);
        lenient().when(tokenCounterService.countTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return switch (text) {
                case "summary", "question" -> 10;
                case "q1", "a1", "q2", "a2", "q3", "a3", "q4", "a4" -> 10;
                default -> text == null ? 0 : 5;
            };
        });
    }

    @Test
    void shouldKeepRecentTwoTurnsAndSummaryFirst() {
        ConversationMemoryPlan plan = planner.plan(buildSnapshot(), "question");

        assertTrue(plan.isSummaryIncluded());
        assertEquals(2, plan.getRecentTurnsKept());
        assertEquals(List.of(
                ChatMessage.system("summary"),
                ChatMessage.user("q3"),
                ChatMessage.assistant("a3"),
                ChatMessage.user("q4"),
                ChatMessage.assistant("a4")
        ), plan.getAnswerHistory());
    }

    @Test
    void shouldIncludeExtraRecentTurnsWhenBudgetAllows() {
        when(memoryProperties.getHistoryBudgetTokens()).thenReturn(70);
        when(ragConfigProperties.getQueryRewriteMaxHistoryTokens()).thenReturn(70);

        ConversationMemoryPlan plan = planner.plan(buildSnapshot(), "question");

        assertEquals(3, plan.getRecentTurnsKept());
        assertEquals(List.of(
                ChatMessage.system("summary"),
                ChatMessage.user("q2"),
                ChatMessage.assistant("a2"),
                ChatMessage.user("q3"),
                ChatMessage.assistant("a3"),
                ChatMessage.user("q4"),
                ChatMessage.assistant("a4")
        ), plan.getAnswerHistory());
    }

    @Test
    void shouldDropSummaryBeforeRecentTurnsWhenBudgetTight() {
        when(memoryProperties.getHistoryBudgetTokens()).thenReturn(35);
        when(ragConfigProperties.getQueryRewriteMaxHistoryTokens()).thenReturn(35);

        ConversationMemoryPlan plan = planner.plan(buildSnapshot(), "question");

        assertFalse(plan.isSummaryIncluded());
        assertEquals(2, plan.getRecentTurnsKept());
        assertEquals(List.of(
                ChatMessage.user("q3"),
                ChatMessage.assistant("a3"),
                ChatMessage.user("q4"),
                ChatMessage.assistant("a4")
        ), plan.getAnswerHistory());
    }

    @Test
    void shouldReturnSummaryWhenOnlySummaryExists() {
        ConversationMemorySnapshot snapshot = ConversationMemorySnapshot.builder()
                .summary(ChatMessage.system("summary"))
                .recentHistory(List.of())
                .build();

        ConversationMemoryPlan plan = planner.plan(snapshot, "question");

        assertEquals(List.of(ChatMessage.system("summary")), plan.getAnswerHistory());
        assertTrue(plan.isSummaryIncluded());
    }

    @Test
    void shouldClampRetrievalTopKToMinimumWhenInputBudgetTight() {
        when(memoryProperties.getInputBudgetTokens()).thenReturn(1050);

        ConversationMemoryPlan plan = planner.plan(buildSnapshot(), "question");

        assertEquals(3, plan.getRetrievalTopK());
        assertEquals(0, plan.getRetrievalBudgetTokens());
    }

    private ConversationMemorySnapshot buildSnapshot() {
        return ConversationMemorySnapshot.builder()
                .summary(ChatMessage.system("summary"))
                .recentHistory(List.of(
                        ChatMessage.user("q1"),
                        ChatMessage.assistant("a1"),
                        ChatMessage.user("q2"),
                        ChatMessage.assistant("a2"),
                        ChatMessage.user("q3"),
                        ChatMessage.assistant("a3"),
                        ChatMessage.user("q4"),
                        ChatMessage.assistant("a4")
                ))
                .build();
    }
}
