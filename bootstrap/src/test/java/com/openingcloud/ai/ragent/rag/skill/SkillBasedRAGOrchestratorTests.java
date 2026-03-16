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

package com.openingcloud.ai.ragent.rag.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandle;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandles;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillBasedRAGOrchestratorTests {

    @Mock
    private LLMService llmService;

    @Mock
    private PromptTemplateLoader promptTemplateLoader;

    @Mock
    private KnowledgeCatalogService catalogService;

    @Mock
    private SkillToolRegistry toolRegistry;

    @Mock
    private SkillToolExecutor toolExecutor;

    @Mock
    private StreamCallback callback;

    private SkillBasedRAGOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setChatKbTemperature(0.3D);
        properties.setChatKbTopP(0.85D);
        properties.setChatMaxTokensKb(1024);
        orchestrator = new SkillBasedRAGOrchestrator(
                llmService, properties, promptTemplateLoader,
                catalogService, toolRegistry, toolExecutor, new ObjectMapper()
        );
        when(catalogService.buildCatalogPrompt()).thenReturn("KB 目录");
        when(toolRegistry.buildToolsPrompt()).thenReturn("工具列表");
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("系统提示词");
    }

    @Test
    void shouldDirectlyAnswerWhenLlmReturnsAnswer() {
        String answerJson = """
                {"type":"answer","content":"这是关于 Spring AOP 的回答"}
                """;
        when(llmService.chat(any(ChatRequest.class))).thenReturn(answerJson);

        StreamCancellationHandle handle = orchestrator.execute(buildRequest("Spring AOP 是什么"));

        assertNotNull(handle);
        verify(callback).onContent("这是关于 Spring AOP 的回答");
        verify(callback).onComplete();
        verify(toolExecutor, never()).execute(anyString(), anyMap(), any());
    }

    @Test
    void shouldExecuteToolCallThenAnswer() {
        String toolCallJson = """
                {"type":"tool_call","tool":"search_kb","args":{"kb_id":"100","query":"Spring AOP","top_k":5}}
                """;
        String answerJson = """
                {"type":"answer","content":"根据检索结果，Spring AOP 基于动态代理实现。"}
                """;
        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn(toolCallJson)
                .thenReturn(answerJson);
        when(toolExecutor.execute(eq("search_kb"), anyMap(), any()))
                .thenReturn(new SkillToolExecutor.ToolExecutionResult(true, "Spring AOP 使用 JDK/CGLIB 动态代理"));

        StreamCancellationHandle handle = orchestrator.execute(buildRequest("Spring AOP 怎么实现的"));

        assertNotNull(handle);
        verify(toolExecutor).execute(eq("search_kb"), anyMap(), any());
        verify(callback).onContent("根据检索结果，Spring AOP 基于动态代理实现。");
        verify(callback).onComplete();
    }

    @Test
    void shouldHandleMultipleToolCallRounds() {
        String firstToolCall = """
                {"type":"tool_call","tool":"search_kb","args":{"kb_id":"100","query":"AOP","top_k":5}}
                """;
        String secondToolCall = """
                {"type":"tool_call","tool":"search_all","args":{"query":"代理模式","top_k":3}}
                """;
        String answerJson = """
                {"type":"answer","content":"综合检索结果，AOP 的核心是代理模式。"}
                """;
        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn(firstToolCall)
                .thenReturn(secondToolCall)
                .thenReturn(answerJson);
        when(toolExecutor.execute(anyString(), anyMap(), any()))
                .thenReturn(new SkillToolExecutor.ToolExecutionResult(true, "结果1"))
                .thenReturn(new SkillToolExecutor.ToolExecutionResult(true, "结果2"));

        orchestrator.execute(buildRequest("AOP 和代理模式的关系"));

        verify(toolExecutor, times(2)).execute(anyString(), anyMap(), any());
        verify(callback).onContent("综合检索结果，AOP 的核心是代理模式。");
    }

    @Test
    void shouldTreatMalformedJsonAsAnswer() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("这不是合法的 JSON，而是自由文本回答。");

        orchestrator.execute(buildRequest("你好"));

        verify(callback).onContent("这不是合法的 JSON，而是自由文本回答。");
        verify(callback).onComplete();
    }

    @Test
    void shouldHandleEmptyLlmResponse() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("");

        orchestrator.execute(buildRequest("测试空响应"));

        verify(callback).onContent("抱歉，未能生成回答。");
        verify(callback).onComplete();
    }

    @Test
    void shouldFallbackToStreamingOnMaxRounds() {
        // 所有5轮都返回工具调用，触发 max rounds
        String toolCallJson = """
                {"type":"tool_call","tool":"search_all","args":{"query":"test","top_k":5}}
                """;
        when(llmService.chat(any(ChatRequest.class))).thenReturn(toolCallJson);
        when(toolExecutor.execute(anyString(), anyMap(), any()))
                .thenReturn(new SkillToolExecutor.ToolExecutionResult(true, "结果"));
        when(llmService.streamChat(any(ChatRequest.class), any(StreamCallback.class)))
                .thenReturn(StreamCancellationHandles.noop());

        StreamCancellationHandle handle = orchestrator.execute(buildRequest("反复检索问题"));

        assertNotNull(handle);
        verify(toolExecutor, times(5)).execute(anyString(), anyMap(), any());
        verify(llmService).streamChat(any(ChatRequest.class), eq(callback));
    }

    @Test
    void shouldHandleExceptionGracefully() {
        when(llmService.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("连接超时"));

        orchestrator.execute(buildRequest("测试异常"));

        verify(callback).onError(any(RuntimeException.class));
    }

    private SkillBasedRAGOrchestrator.SkillExecuteRequest buildRequest(String question) {
        return SkillBasedRAGOrchestrator.SkillExecuteRequest.builder()
                .question(question)
                .conversationId("conv-1")
                .userId("user-1")
                .history(List.of())
                .emitter(new SseEmitter(0L))
                .callback(callback)
                .token(CancellationToken.NONE)
                .build();
    }
}
