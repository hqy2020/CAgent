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

import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.token.TokenCounterService;
import com.openingcloud.ai.ragent.rag.config.MemoryProperties;
import com.openingcloud.ai.ragent.rag.core.memory.MySQLConversationMemorySummaryService;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.openingcloud.ai.ragent.rag.service.ConversationGroupService;
import com.openingcloud.ai.ragent.rag.service.ConversationMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySQLConversationMemorySummaryServiceTests {

    @Mock
    private ConversationGroupService conversationGroupService;
    @Mock
    private ConversationMessageService conversationMessageService;
    @Mock
    private MemoryProperties memoryProperties;
    @Mock
    private LLMService llmService;
    @Mock
    private PromptTemplateLoader promptTemplateLoader;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private TokenCounterService tokenCounterService;
    @Mock
    private RLock lock;

    private MySQLConversationMemorySummaryService service;

    @BeforeEach
    void setUp() throws Exception {
        Executor directExecutor = Runnable::run;
        service = new MySQLConversationMemorySummaryService(
                conversationGroupService,
                conversationMessageService,
                memoryProperties,
                llmService,
                promptTemplateLoader,
                redissonClient,
                tokenCounterService,
                directExecutor
        );
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(eq(0L), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(memoryProperties.getSummaryEnabled()).thenReturn(true);
        lenient().when(memoryProperties.getHistoryKeepTurns()).thenReturn(2);
        lenient().when(memoryProperties.getSummaryStartTurns()).thenReturn(3);
        lenient().when(memoryProperties.getSummaryMaxChars()).thenReturn(200);
        lenient().when(promptTemplateLoader.render(anyString(), any())).thenReturn("summary prompt");
        lenient().when(tokenCounterService.countTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text == null) {
                return 0;
            }
            return Math.max(1, text.length() / 4);
        });
    }

    @Test
    void shouldSkipCompressionWhenPendingTokensBelowThreshold() {
        when(memoryProperties.getSummaryTriggerTokens()).thenReturn(200);
        when(conversationGroupService.countUserMessages("conv-1", "user-1")).thenReturn(3L);
        when(conversationGroupService.listLatestUserOnlyMessages("conv-1", "user-1", 2)).thenReturn(List.of(
                message(30L, "user", "u3"),
                message(20L, "user", "u2")
        ));
        when(conversationGroupService.listMessagesBetweenIds("conv-1", "user-1", null, 20L)).thenReturn(List.of(
                message(10L, "user", "old question"),
                message(11L, "assistant", "old answer")
        ));

        service.compressIfNeeded("conv-1", "user-1", ChatMessage.assistant("latest answer"));

        verify(llmService, never()).chat(any(ChatRequest.class));
        verify(conversationMessageService, never()).addMessageSummary(any());
    }

    @Test
    void shouldCompressWhenPendingTokensReachThreshold() {
        when(memoryProperties.getSummaryTriggerTokens()).thenReturn(5);
        when(tokenCounterService.countTokens("iPhone 16 Pro 多少钱")).thenReturn(4);
        when(tokenCounterService.countTokens("7999 元")).thenReturn(4);
        when(conversationGroupService.countUserMessages("conv-2", "user-2")).thenReturn(3L);
        when(conversationGroupService.listLatestUserOnlyMessages("conv-2", "user-2", 2)).thenReturn(List.of(
                message(30L, "user", "u3"),
                message(20L, "user", "u2")
        ));
        when(conversationGroupService.listMessagesBetweenIds("conv-2", "user-2", null, 20L)).thenReturn(List.of(
                message(10L, "user", "iPhone 16 Pro 多少钱"),
                message(11L, "assistant", "7999 元")
        ));
        when(llmService.chat(any(ChatRequest.class))).thenReturn("历史讨论：用户咨询了 iPhone 16 Pro 价格（已解答）。实体：iPhone 16 Pro。");

        service.compressIfNeeded("conv-2", "user-2", ChatMessage.assistant("latest answer"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(captor.capture());
        assertTrue(captor.getValue().getMessages().stream()
                .anyMatch(message -> message.getContent().contains("iPhone 16 Pro 多少钱")));
        verify(conversationMessageService).addMessageSummary(any());
    }

    @Test
    void shouldUseExistingSummaryTokensWhenCheckingThreshold() {
        when(memoryProperties.getSummaryTriggerTokens()).thenReturn(15);
        when(tokenCounterService.countTokens("历史讨论：用户咨询了旧型号（已解答）。")).thenReturn(8);
        when(tokenCounterService.countTokens("保修期呢")).thenReturn(4);
        when(tokenCounterService.countTokens("一年")).thenReturn(4);
        when(conversationGroupService.countUserMessages("conv-3", "user-3")).thenReturn(4L);
        when(conversationGroupService.findLatestSummary("conv-3", "user-3"))
                .thenReturn(ConversationSummaryDO.builder()
                        .conversationId("conv-3")
                        .userId("user-3")
                        .content("历史讨论：用户咨询了旧型号（已解答）。")
                        .lastMessageId(5L)
                        .build());
        when(conversationGroupService.listLatestUserOnlyMessages("conv-3", "user-3", 2)).thenReturn(List.of(
                message(40L, "user", "u4"),
                message(30L, "user", "u3")
        ));
        when(conversationGroupService.listMessagesBetweenIds("conv-3", "user-3", 5L, 30L)).thenReturn(List.of(
                message(10L, "user", "保修期呢"),
                message(11L, "assistant", "一年")
        ));
        when(llmService.chat(any(ChatRequest.class))).thenReturn("历史讨论：用户咨询了旧型号价格（已解答）、保修期（已解答）。");

        service.compressIfNeeded("conv-3", "user-3", ChatMessage.assistant("latest answer"));

        verify(llmService).chat(any(ChatRequest.class));
        verify(conversationMessageService).addMessageSummary(any());
    }

    private ConversationMessageDO message(Long id, String role, String content) {
        return ConversationMessageDO.builder()
                .id(id)
                .conversationId("conv")
                .userId("user")
                .role(role)
                .content(content)
                .build();
    }
}
