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
import com.nageoffer.ai.ragent.rag.agent.AgentCommandRouter;
import com.nageoffer.ai.ragent.rag.agent.AgentModeDecider;
import com.nageoffer.ai.ragent.rag.agent.AgentModeDecision;
import com.nageoffer.ai.ragent.rag.agent.AgentOrchestrator;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private RAGConfigProperties ragConfigProperties;
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
    @Mock
    private AgentCommandRouter agentCommandRouter;
    @Mock
    private AgentModeDecider agentModeDecider;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @InjectMocks
    private RAGChatServiceImpl ragChatService;

    @Test
    void testRetrieveFailureShouldFallbackToSystemResponse() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        StreamCancellationHandle handle = org.mockito.Mockito.mock(StreamCancellationHandle.class);

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题", List.of())));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(agentModeDecider.decide(anyString(), anyList(), any())).thenReturn(AgentModeDecision.disabled("test"));
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
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
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

    @Test
    void testAgentRouteShouldShortCircuitRagPipeline() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(true);

        ragChatService.streamChat("/qy-review smoke", null, false, new SseEmitter(0L));

        verify(agentCommandRouter).tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class));
        verify(queryRewriteService, never()).rewriteWithSplit(anyString(), anyList());
        verify(intentResolver, never()).resolve(any(RewriteResult.class), any(CancellationToken.class));
        verify(retrievalEngine, never()).retrieve(anyList(), anyInt(), any(CancellationToken.class));
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
        verify(taskManager, never()).bindHandle(anyString(), any());
    }

    @Test
    void testAgentModeShouldBeHandledByOrchestrator() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题", List.of())));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(anyList(), anyInt(), any(CancellationToken.class)))
                .thenReturn(com.nageoffer.ai.ragent.rag.dto.RetrievalContext.builder()
                        .kbContext("kb")
                        .mcpContext("")
                        .intentChunks(java.util.Map.of())
                        .build());
        when(agentModeDecider.decide(anyString(), anyList(), any())).thenReturn(AgentModeDecision.enabled("multi-step", 0.2));
        when(agentOrchestrator.execute(any())).thenReturn(true);

        ragChatService.streamChat("请帮我先检索再整理", null, false, new SseEmitter(0L));

        verify(agentModeDecider).decide(eq("请帮我先检索再整理"), anyList(), any());
        verify(agentOrchestrator).execute(any());
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    @Test
    void testDateTimeQuestionShouldBypassRagPipeline() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);

        ragChatService.streamChat("今天几号", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("今天是"));
        verify(callback).onComplete();
        verify(queryRewriteService, never()).rewriteWithSplit(anyString(), anyList());
        verify(intentResolver, never()).resolve(any(RewriteResult.class), any(CancellationToken.class));
        verify(retrievalEngine, never()).retrieve(anyList(), anyInt(), any(CancellationToken.class));
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    @Test
    void testDateMutationQuestionShouldNotUseDateTimeShortcut() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("帮我创建今天的日记", List.of("帮我创建今天的日记")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("帮我创建今天的日记", List.of())));
        when(guidanceService.detectAmbiguity(anyString(), anyList()))
                .thenReturn(GuidanceDecision.prompt("请补充笔记标题"));

        ragChatService.streamChat("帮我创建今天的日记", null, false, new SseEmitter(0L));

        verify(queryRewriteService).rewriteWithSplit(eq("帮我创建今天的日记"), anyList());
        verify(callback).onContent("请补充笔记标题");
        verify(callback).onComplete();
    }

    @Test
    void testWebNewsMcpContextShouldDirectReplyWithoutLlmRewrite() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        when(memoryService.loadAndAppend(anyString(), any(), any()))
                .thenReturn(List.of(ChatMessage.user("history")));
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("帮我联网搜索一下今天 AI 领域的 3 条新闻", List.of("帮我联网搜索一下今天 AI 领域的 3 条新闻")));
        IntentNode webNewsNode = IntentNode.builder()
                .id("web-search-news")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
                .build();
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent(
                        "帮我联网搜索一下今天 AI 领域的 3 条新闻",
                        List.of(NodeScore.builder().node(webNewsNode).score(0.91D).build())
                )));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(anyList(), anyInt(), any(CancellationToken.class)))
                .thenReturn(RetrievalContext.builder()
                        .kbContext("")
                        .mcpContext("""
                                ---
                                **子问题**：帮我联网搜索一下今天 AI 领域的 3 条新闻

                                **相关文档**：
                                #### 动态数据片段
                                联网检索结果（关键词：AI）：
                                1. 标题：A
                                   来源链接：https://example.com/a
                                   发布日期：2026-03-05
                                2. 标题：B
                                   来源链接：https://example.com/b
                                   发布日期：2026-03-05
                                3. 标题：C
                                   来源链接：https://example.com/c
                                   发布日期：2026-03-05
                                """)
                        .intentChunks(java.util.Map.of())
                        .build());

        ragChatService.streamChat("帮我联网搜索一下今天 AI 领域的 3 条新闻", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("联网检索结果（关键词：AI）"));
        verify(callback).onContent(contains("来源链接：https://example.com/a"));
        verify(callback).onComplete();
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    private void mockChatConfig() {
        when(ragConfigProperties.getChatSystemTemperature()).thenReturn(0.7D);
        when(ragConfigProperties.getChatSystemTopP()).thenReturn(0.8D);
        when(ragConfigProperties.getChatMaxTokensSystem()).thenReturn(2048);
        when(ragConfigProperties.getChatKbTemperature()).thenReturn(0.3D);
        when(ragConfigProperties.getChatKbTopP()).thenReturn(0.85D);
        when(ragConfigProperties.getChatMaxTokensKb()).thenReturn(2048);
    }
}
