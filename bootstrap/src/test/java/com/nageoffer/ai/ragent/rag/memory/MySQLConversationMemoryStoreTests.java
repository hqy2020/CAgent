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
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.core.memory.MySQLConversationMemoryStore;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MySQLConversationMemoryStoreTests {

    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationMessageService conversationMessageService;
    @Mock
    private MemoryProperties memoryProperties;

    private MySQLConversationMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MySQLConversationMemoryStore(conversationService, conversationMessageService, memoryProperties);
        when(memoryProperties.getHistoryKeepTurns()).thenReturn(5);
    }

    @Test
    void testLoadHistoryCallsFourParamListMessages() {
        List<ConversationMessageVO> mockMessages = List.of(
                ConversationMessageVO.builder()
                        .id("1").conversationId("conv-1").role("user")
                        .content("hello").createTime(new Date()).build(),
                ConversationMessageVO.builder()
                        .id("2").conversationId("conv-1").role("assistant")
                        .content("hi there").createTime(new Date()).build()
        );

        // 4 参数版本应被调用（显式传入 userId）
        when(conversationMessageService.listMessages(
                eq("conv-1"), eq("user-1"), eq(10), eq(ConversationMessageOrder.DESC)
        )).thenReturn(mockMessages);

        List<ChatMessage> result = store.loadHistory("conv-1", "user-1");

        // 验证调用的是 4 参数版本
        verify(conversationMessageService).listMessages(
                eq("conv-1"), eq("user-1"), eq(10), eq(ConversationMessageOrder.DESC)
        );
        // 验证 3 参数版本从未被调用
        verify(conversationMessageService, never()).listMessages(
                eq("conv-1"), eq(10), eq(ConversationMessageOrder.DESC)
        );

        assertEquals(2, result.size());
        assertEquals(ChatMessage.Role.USER, result.get(0).getRole());
        assertEquals("hello", result.get(0).getContent());
    }

    @Test
    void testLoadHistoryReturnsEmptyWhenNoMessages() {
        when(conversationMessageService.listMessages(
                eq("conv-2"), eq("user-2"), eq(10), eq(ConversationMessageOrder.DESC)
        )).thenReturn(List.of());

        List<ChatMessage> result = store.loadHistory("conv-2", "user-2");

        assertTrue(result.isEmpty());
    }

    @Test
    void testLoadHistoryNormalizesStartingWithAssistant() {
        // 历史以 assistant 消息开头 → 应被裁剪掉
        List<ConversationMessageVO> mockMessages = List.of(
                ConversationMessageVO.builder()
                        .id("1").conversationId("conv-3").role("assistant")
                        .content("orphan response").createTime(new Date()).build(),
                ConversationMessageVO.builder()
                        .id("2").conversationId("conv-3").role("user")
                        .content("real question").createTime(new Date()).build(),
                ConversationMessageVO.builder()
                        .id("3").conversationId("conv-3").role("assistant")
                        .content("real answer").createTime(new Date()).build()
        );

        when(conversationMessageService.listMessages(
                eq("conv-3"), eq("user-3"), eq(10), eq(ConversationMessageOrder.DESC)
        )).thenReturn(mockMessages);

        List<ChatMessage> result = store.loadHistory("conv-3", "user-3");

        assertEquals(2, result.size());
        assertEquals(ChatMessage.Role.USER, result.get(0).getRole());
        assertEquals("real question", result.get(0).getContent());
    }
}
