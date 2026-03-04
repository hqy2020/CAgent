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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 本地无外部依赖的占位 ChatClient
 * 用于联调或集成测试，避免因为第三方模型凭据缺失导致链路不可执行
 */
@Component
public class NoopChatClient implements ChatClient {

    @Override
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        return pickResponse(request);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        try {
            String content = pickResponse(request);
            if (StringUtils.hasText(content)) {
                callback.onContent(content);
            }
            callback.onComplete();
        } catch (Exception e) {
            callback.onError(e);
        }
        return StreamCancellationHandles.noop();
    }

    private String pickResponse(ChatRequest request) {
        if (request == null) {
            return "";
        }
        List<ChatMessage> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            if (message.getRole() == ChatMessage.Role.USER) {
                return message.getContent();
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && StringUtils.hasText(message.getContent())) {
                return message.getContent();
            }
        }
        return "";
    }
}
