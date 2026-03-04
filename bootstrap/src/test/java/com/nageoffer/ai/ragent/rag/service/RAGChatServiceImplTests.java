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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGChatServiceImplTests {

    @Mock
    private LLMService llmService;
    @Mock
    private RAGPromptService promptBuilder;
    @Mock
    private PromptTemplateLoader promptTemplateLoader;
    @Mock
    private ConversationMemoryService memoryService;
    @Mock
    private StreamTaskManager taskManager;
    @Mock
    private IntentGuidanceService guidanceService;
    @Mock
    private StreamCallbackFactory callbackFactory;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private IntentResolver intentResolver;
    @Mock
    private RetrievalEngine retrievalEngine;
    @Mock
    private ConversationService conversationService;

    @InjectMocks
    private RAGChatServiceImpl ragChatService;

    @Test
    void testRetrieveFailureShouldFallbackToSystemResponse() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        StreamCancellationHandle handle = org.mockito.Mockito.mock(StreamCancellationHandle.class);

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题", List.of())));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(anyList(), anyInt(), any(CancellationToken.class))).thenThrow(new RuntimeException("milvus unavailable"));
        when(promptTemplateLoader.load(anyString())).thenReturn("system prompt");
        when(llmService.streamChat(any(ChatRequest.class), same(callback))).thenReturn(handle);

        ragChatService.streamChat("你好", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("知识库检索服务暂时不可用"));
        verify(llmService).streamChat(any(ChatRequest.class), same(callback));
        verify(taskManager).bindHandle(anyString(), same(handle));
    }

    @Test
    void testStreamStartFailureShouldTriggerCallbackError() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题", List.of())));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(true);
        when(promptTemplateLoader.load(anyString())).thenReturn("system prompt");
        when(llmService.streamChat(any(ChatRequest.class), same(callback))).thenThrow(new RuntimeException("llm down"));

        ragChatService.streamChat("你好", null, false, new SseEmitter(0L));

        verify(callback).onError(any(RuntimeException.class));
        verify(taskManager, never()).bindHandle(anyString(), any());
    }
}
