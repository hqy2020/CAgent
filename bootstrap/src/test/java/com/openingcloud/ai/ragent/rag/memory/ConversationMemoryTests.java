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

package com.openingcloud.ai.ragent.rag.memory;

import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemorySnapshot;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryStore;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemorySummaryService;
import com.openingcloud.ai.ragent.rag.core.memory.DefaultConversationMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryTests {

    @Mock
    private ConversationMemoryStore memoryStore;
    @Mock
    private ConversationMemorySummaryService summaryService;
    @Mock
    private Executor memoryLoadExecutor;

    private DefaultConversationMemoryService service;

    @BeforeEach
    void setUp() {
        // 让 mock Executor 直接在调用线程执行，简化测试
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(memoryLoadExecutor).execute(any(Runnable.class));

        service = new DefaultConversationMemoryService(memoryStore, summaryService, memoryLoadExecutor);
    }

    @Test
    void testLoadParallelSummaryAndHistory() {
        ChatMessage summary = ChatMessage.system("Previous conversation summary...");
        ChatMessage decorated = ChatMessage.system("[Summary] Previous conversation summary...");
        List<ChatMessage> history = List.of(
                ChatMessage.user("hello"),
                ChatMessage.assistant("hi there")
        );

        when(summaryService.loadLatestSummary("conv-1", "user-1")).thenReturn(summary);
        when(summaryService.decorateIfNeeded(summary)).thenReturn(decorated);
        when(memoryStore.loadHistory("conv-1", "user-1")).thenReturn(history);

        List<ChatMessage> result = service.load("conv-1", "user-1");

        assertEquals(3, result.size());
        assertEquals(ChatMessage.Role.SYSTEM, result.get(0).getRole());
        assertEquals("hello", result.get(1).getContent());
        assertEquals("hi there", result.get(2).getContent());
    }

    @Test
    void testLoadSnapshotShouldKeepDecoratedSummary() {
        ChatMessage summary = ChatMessage.system("raw summary");
        ChatMessage decorated = ChatMessage.system("对话摘要：raw summary");
        when(summaryService.loadLatestSummary("conv-s1", "user-s1")).thenReturn(summary);
        when(summaryService.decorateIfNeeded(summary)).thenReturn(decorated);
        when(memoryStore.loadHistory("conv-s1", "user-s1")).thenReturn(List.of(ChatMessage.user("hello")));

        ConversationMemorySnapshot snapshot = service.loadSnapshot("conv-s1", "user-s1");

        assertNotNull(snapshot.getSummary());
        assertEquals("对话摘要：raw summary", snapshot.getSummary().getContent());
        assertEquals(1, snapshot.getRecentHistory().size());
    }

    @Test
    void testLoadFallbackWhenSummaryFails() {
        List<ChatMessage> history = List.of(ChatMessage.user("test"));

        when(summaryService.loadLatestSummary("conv-2", "user-2"))
                .thenThrow(new RuntimeException("Redis connection refused"));
        when(memoryStore.loadHistory("conv-2", "user-2")).thenReturn(history);

        List<ChatMessage> result = service.load("conv-2", "user-2");

        assertEquals(1, result.size());
        assertEquals("test", result.get(0).getContent());
    }

    @Test
    void testLoadReturnsEmptyForBlankParams() {
        List<ChatMessage> result1 = service.load("", "user-1");
        List<ChatMessage> result2 = service.load("conv-1", "");
        List<ChatMessage> result3 = service.load(null, "user-1");

        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
        assertTrue(result3.isEmpty());

        verify(memoryStore, never()).loadHistory(any(), any());
    }

    @Test
    void testAppendTriggersCompressIfNeeded() {
        ChatMessage msg = ChatMessage.user("new question");
        when(memoryStore.append("conv-3", "user-3", msg)).thenReturn(12345L);

        Long messageId = service.append("conv-3", "user-3", msg);

        assertEquals(12345L, messageId);
        verify(memoryStore).append("conv-3", "user-3", msg);
        verify(summaryService).compressIfNeeded(eq("conv-3"), eq("user-3"), eq(msg));
    }

    @Test
    void testAppendReturnsNullForBlankParams() {
        Long result = service.append("", "user-1", ChatMessage.user("msg"));

        assertNull(result);
        verify(memoryStore, never()).append(any(), any(), any());
    }

    @Test
    void testLoadHistoryFailureReturnsEmpty() {
        when(summaryService.loadLatestSummary("conv-4", "user-4")).thenReturn(null);
        when(memoryStore.loadHistory("conv-4", "user-4"))
                .thenThrow(new RuntimeException("DB error"));

        List<ChatMessage> result = service.load("conv-4", "user-4");

        assertTrue(result.isEmpty());
    }

    @Test
    void testLoadShouldReturnSummaryEvenWhenHistoryEmpty() {
        ChatMessage summary = ChatMessage.system("raw summary");
        ChatMessage decorated = ChatMessage.system("对话摘要：raw summary");
        when(summaryService.loadLatestSummary("conv-summary", "user-summary")).thenReturn(summary);
        when(summaryService.decorateIfNeeded(summary)).thenReturn(decorated);
        when(memoryStore.loadHistory("conv-summary", "user-summary")).thenReturn(List.of());

        List<ChatMessage> result = service.load("conv-summary", "user-summary");

        assertEquals(1, result.size());
        assertEquals("对话摘要：raw summary", result.get(0).getContent());
    }

    @Test
    void testLoadUsesInjectedExecutor() {
        when(summaryService.loadLatestSummary("conv-5", "user-5")).thenReturn(null);
        when(memoryStore.loadHistory("conv-5", "user-5")).thenReturn(List.of(ChatMessage.user("hi")));

        service.load("conv-5", "user-5");

        // 并行加载摘要和历史 → executor 应被调用至少 2 次
        verify(memoryLoadExecutor, atLeast(2)).execute(any(Runnable.class));
    }
}
