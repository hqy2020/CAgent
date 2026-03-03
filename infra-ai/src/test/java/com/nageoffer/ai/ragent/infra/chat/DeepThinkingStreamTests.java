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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 深度思考模式流式传输集成测试
 * 使用 MockWebServer 模拟 SSE 响应，验证 SiliconFlowChatClient 和 MiniMaxChatClient
 * 的 reasoningEnabled 参数正确传递（bug 修复验证）
 */
class DeepThinkingStreamTests {

    private MockWebServer server;
    private OkHttpClient httpClient;

    /**
     * 包含 reasoning_content 的 SSE 流响应
     */
    private static final String SSE_WITH_REASONING = String.join("\n",
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"让我分析一下这个问题\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Spring 是一个 Java 框架\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"Spring 是一个\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"开源框架\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            ""
    );

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        httpClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ─── SiliconFlowChatClient 测试 ───

    @Test
    @DisplayName("SiliconFlow: thinking=false 时不应触发 onThinking（bug 修复验证）")
    void siliconFlow_thinkingDisabled_shouldNotCallOnThinking() throws Exception {
        server.enqueue(sseResponse(SSE_WITH_REASONING));

        SiliconFlowChatClient client = new SiliconFlowChatClient(httpClient, Executors.newSingleThreadExecutor());
        ChatRequest request = buildRequest(false);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request, callback, buildTarget());
        callback.awaitComplete(5);

        assertTrue(callback.thinkingChunks.isEmpty(),
                "thinking=false 时 onThinking 不应被调用，但收到: " + callback.thinkingChunks);
        assertFalse(callback.contentChunks.isEmpty(), "应收到 content 内容");
        assertEquals("Spring 是一个开源框架", String.join("", callback.contentChunks));
        assertTrue(callback.completed, "应收到 onComplete");
    }

    @Test
    @DisplayName("SiliconFlow: thinking=true 时应触发 onThinking")
    void siliconFlow_thinkingEnabled_shouldCallOnThinking() throws Exception {
        server.enqueue(sseResponse(SSE_WITH_REASONING));

        SiliconFlowChatClient client = new SiliconFlowChatClient(httpClient, Executors.newSingleThreadExecutor());
        ChatRequest request = buildRequest(true);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request, callback, buildTarget());
        callback.awaitComplete(5);

        assertFalse(callback.thinkingChunks.isEmpty(), "thinking=true 时应收到 onThinking 调用");
        assertEquals("让我分析一下这个问题Spring 是一个 Java 框架",
                String.join("", callback.thinkingChunks));
        assertFalse(callback.contentChunks.isEmpty());
        assertTrue(callback.completed);
    }

    @Test
    @DisplayName("SiliconFlow: thinking=null 时不应触发 onThinking")
    void siliconFlow_thinkingNull_shouldNotCallOnThinking() throws Exception {
        server.enqueue(sseResponse(SSE_WITH_REASONING));

        SiliconFlowChatClient client = new SiliconFlowChatClient(httpClient, Executors.newSingleThreadExecutor());
        ChatRequest request = buildRequest(null);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request, callback, buildTarget());
        callback.awaitComplete(5);

        assertTrue(callback.thinkingChunks.isEmpty(),
                "thinking=null 时 onThinking 不应被调用");
    }

    // ─── MiniMaxChatClient 测试 ───

    @Test
    @DisplayName("MiniMax: thinking=false 时不应触发 onThinking（bug 修复验证）")
    void miniMax_thinkingDisabled_shouldNotCallOnThinking() throws Exception {
        server.enqueue(sseResponse(SSE_WITH_REASONING));

        MiniMaxChatClient client = new MiniMaxChatClient(httpClient, Executors.newSingleThreadExecutor());
        ChatRequest request = buildRequest(false);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request, callback, buildTarget());
        callback.awaitComplete(5);

        assertTrue(callback.thinkingChunks.isEmpty(),
                "thinking=false 时 onThinking 不应被调用，但收到: " + callback.thinkingChunks);
        assertFalse(callback.contentChunks.isEmpty());
        assertTrue(callback.completed);
    }

    @Test
    @DisplayName("MiniMax: thinking=true 时应触发 onThinking")
    void miniMax_thinkingEnabled_shouldCallOnThinking() throws Exception {
        server.enqueue(sseResponse(SSE_WITH_REASONING));

        MiniMaxChatClient client = new MiniMaxChatClient(httpClient, Executors.newSingleThreadExecutor());
        ChatRequest request = buildRequest(true);
        RecordingCallback callback = new RecordingCallback();

        client.streamChat(request, callback, buildTarget());
        callback.awaitComplete(5);

        assertFalse(callback.thinkingChunks.isEmpty(), "thinking=true 时应收到 onThinking 调用");
        assertTrue(callback.completed);
    }

    // ─── 辅助方法 ───

    private ChatRequest buildRequest(Boolean thinking) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user("什么是Spring")))
                .thinking(thinking)
                .build();
    }

    private ModelTarget buildTarget() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setApiKey("test-key");
        provider.setUrl(server.url("/").toString());
        provider.setEndpoints(java.util.Map.of("chat", "/v1/chat/completions"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("test-model");
        // 直接指定 URL，绕过 endpoint 拼接
        candidate.setUrl(server.url("/v1/chat/completions").toString());

        return new ModelTarget("test", candidate, provider);
    }

    private MockResponse sseResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }

    /**
     * 记录型 StreamCallback 实现
     * 收集所有回调调用，用于断言验证
     */
    static class RecordingCallback implements StreamCallback {

        final List<String> contentChunks = Collections.synchronizedList(new ArrayList<>());
        final List<String> thinkingChunks = Collections.synchronizedList(new ArrayList<>());
        volatile boolean completed = false;
        volatile Throwable error = null;
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onContent(String content) {
            contentChunks.add(content);
        }

        @Override
        public void onThinking(String content) {
            thinkingChunks.add(content);
        }

        @Override
        public void onComplete() {
            completed = true;
            latch.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            latch.countDown();
        }

        void awaitComplete(int seconds) throws InterruptedException {
            assertTrue(latch.await(seconds, TimeUnit.SECONDS), "流式响应超时");
            if (error != null) {
                fail("流式响应出错: " + error.getMessage());
            }
        }
    }
}
