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

package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamChatEventHandlerTests {

    @Test
    void testOnErrorShouldSendErrorAndDoneAndComplete() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);

        when(conversationGroupService.findConversation(anyString(), nullable(String.class))).thenReturn(null);
        when(taskManager.isCancelled("task-1")).thenReturn(false);

        AIModelProperties modelProperties = new AIModelProperties();
        modelProperties.getStream().setMessageChunkSize(1);

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("conv-1")
                .taskId("task-1")
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .build();

        StreamChatEventHandler handler = new StreamChatEventHandler(params);
        handler.onError(new RuntimeException("boom"));

        verify(taskManager).register(anyString(), any(), any());
        verify(taskManager).unregister("task-1");
        assertTrue(emitter.completed);
        assertNull(emitter.completedWithError);
        assertEquals(3, emitter.eventNames.size());
        assertTrue(emitter.rawPayloads.stream().anyMatch(payload -> payload instanceof Map));
        assertTrue(emitter.rawPayloads.stream().anyMatch(payload -> "[DONE]".equals(payload)));
    }

    @Test
    void testOnCompleteShouldSendFinishAndDoneAndComplete() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);

        when(conversationGroupService.findConversation(anyString(), nullable(String.class))).thenReturn(null);
        when(taskManager.isCancelled("task-2")).thenReturn(false);
        when(memoryService.append(anyString(), nullable(String.class), any())).thenReturn(1001L);

        AIModelProperties modelProperties = new AIModelProperties();
        modelProperties.getStream().setMessageChunkSize(1);

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("conv-2")
                .taskId("task-2")
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .build();

        StreamChatEventHandler handler = new StreamChatEventHandler(params);
        handler.onContent("ok");
        handler.onComplete();

        verify(taskManager).register(anyString(), any(), any());
        verify(taskManager).unregister("task-2");
        verify(memoryService).append(anyString(), nullable(String.class), any());
        assertTrue(emitter.completed);
        assertNull(emitter.completedWithError);
        assertTrue(emitter.rawPayloads.stream().anyMatch(payload -> payload instanceof CompletionPayload));
        assertTrue(emitter.rawPayloads.stream().anyMatch(payload -> "[DONE]".equals(payload)));
    }

    private static final class CapturingSseEmitter extends SseEmitter {
        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> rawPayloads = new ArrayList<>();
        private boolean completed;
        private Throwable completedWithError;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> eventParts = builder.build();
            eventNames.add(extractEventName(eventParts));
            for (ResponseBodyEmitter.DataWithMediaType each : eventParts) {
                rawPayloads.add(each.getData());
            }
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = ex;
        }

        private String extractEventName(Set<ResponseBodyEmitter.DataWithMediaType> eventParts) {
            for (ResponseBodyEmitter.DataWithMediaType each : eventParts) {
                Object data = each.getData();
                if (data instanceof String line && line.startsWith("event:")) {
                    return line.substring("event:".length()).trim();
                }
            }
            return "";
        }
    }
}
