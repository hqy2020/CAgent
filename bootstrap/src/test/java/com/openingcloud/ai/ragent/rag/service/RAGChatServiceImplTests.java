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

package com.openingcloud.ai.ragent.rag.service;

import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandle;
import com.openingcloud.ai.ragent.rag.agent.AgentCommandRouter;
import com.openingcloud.ai.ragent.rag.agent.AgentModeDecider;
import com.openingcloud.ai.ragent.rag.agent.AgentModeDecision;
import com.openingcloud.ai.ragent.rag.agent.AgentOrchestrator;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.openingcloud.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryPlan;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryPlanner;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemorySnapshot;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.core.prompt.RAGPromptService;
import com.openingcloud.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.openingcloud.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.openingcloud.ai.ragent.rag.core.rewrite.RewriteResult;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.openingcloud.ai.ragent.rag.service.handler.StreamTaskManager;
import com.openingcloud.ai.ragent.rag.service.impl.RAGChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private ConversationMemoryPlanner memoryPlanner;
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
        IntentNode kbNode = IntentNode.builder().id("kb-intent").kind(IntentKind.KB).build();

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题",
                        List.of(NodeScore.builder().node(kbNode).score(0.9D).build()))));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(agentModeDecider.decide(anyString(), anyList(), any())).thenReturn(AgentModeDecision.disabled("test"));
        when(retrievalEngine.retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class))).thenThrow(new RuntimeException("milvus unavailable"));
        when(promptTemplateLoader.load(anyString())).thenReturn("system prompt");
        when(llmService.streamChat(any(ChatRequest.class), any(StreamCallback.class))).thenReturn(handle);

        ragChatService.streamChat("请说明退款规则", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("知识库检索服务暂时不可用"));
        verify(llmService).streamChat(any(ChatRequest.class), any(StreamCallback.class));
        verify(taskManager).bindHandle(anyString(), same(handle));
    }

    @Test
    void testStreamStartFailureShouldTriggerCallbackError() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题", List.of())));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(true);
        when(promptTemplateLoader.load(anyString())).thenReturn("system prompt");
        when(llmService.streamChat(any(ChatRequest.class), any(StreamCallback.class))).thenThrow(new RuntimeException("llm down"));

        ragChatService.streamChat("请总结退款规则", null, false, new SseEmitter(0L));

        verify(callback).onError(any(RuntimeException.class));
        verify(taskManager, never()).bindHandle(anyString(), any());
    }

    @Test
    void testAgentRouteShouldShortCircuitRagPipeline() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);

        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(true);

        ragChatService.streamChat("/qy-review smoke", null, false, new SseEmitter(0L));

        verify(agentCommandRouter).tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class));
        verify(queryRewriteService, never()).rewriteWithSplit(anyString(), anyList());
        verify(intentResolver, never()).resolve(any(RewriteResult.class), any(CancellationToken.class));
        verify(retrievalEngine, never()).retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class));
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
        verify(taskManager, never()).bindHandle(anyString(), any());
    }

    @Test
    void testAgentModeShouldBeHandledByOrchestrator() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        IntentNode kbNode = IntentNode.builder().id("kb-intent").kind(IntentKind.KB).build();
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", List.of("改写问题")));
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent("改写问题",
                        List.of(NodeScore.builder().node(kbNode).score(0.9D).build()))));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class)))
                .thenReturn(com.openingcloud.ai.ragent.rag.dto.RetrievalContext.builder()
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
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);

        ragChatService.streamChat("今天几号", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("今天是"));
        verify(callback).onComplete();
        verify(queryRewriteService, never()).rewriteWithSplit(anyString(), anyList());
        verify(intentResolver, never()).resolve(any(RewriteResult.class), any(CancellationToken.class));
        verify(retrievalEngine, never()).retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class));
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    @Test
    void testDateMutationQuestionShouldNotUseDateTimeShortcut() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
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
        mockMemoryPlan();
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
        when(retrievalEngine.retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class)))
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

    @Test
    void testRealtimeMcpContextShouldDirectReplyWithoutLlmRewrite() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("今天上海天气怎么样", List.of("今天上海天气怎么样")));
        IntentNode realtimeNode = IntentNode.builder()
                .id("web-search-realtime")
                .kind(IntentKind.MCP)
                .mcpToolId("web_realtime_search")
                .build();
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent(
                        "今天上海天气怎么样",
                        List.of(NodeScore.builder().node(realtimeNode).score(0.95D).build())
                )));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class)))
                .thenReturn(RetrievalContext.builder()
                        .kbContext("")
                        .mcpContext("""
                                ---
                                **子问题**：今天上海天气怎么样

                                **相关文档**：
                                联网实时信息结果（关键词：上海天气）：
                                1. 标题：上海 当前天气
                                   摘要：上海当前晴，10.9°C，体感10.3°C。
                                   来源：Open-Meteo
                                   来源链接：https://open-meteo.com/en/docs
                                   发布日期：2026-03-10
                                """)
                        .intentChunks(java.util.Map.of())
                        .build());

        ragChatService.streamChat("今天上海天气怎么样", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("联网实时信息结果（关键词：上海天气）"));
        verify(callback).onContent(contains("上海当前晴，10.9°C"));
        verify(callback).onComplete();
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    @Test
    void testEmptyGeneralWebSearchContextShouldFallbackToSystemResponse() {
        StreamCallback callback = org.mockito.Mockito.mock(StreamCallback.class);
        StreamCancellationHandle handle = org.mockito.Mockito.mock(StreamCancellationHandle.class);
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        mockChatConfig();
        when(taskManager.createToken(anyString())).thenReturn(CancellationToken.NONE);
        mockMemoryPlan();
        when(agentCommandRouter.tryRoute(anyString(), anyString(), any(), anyString(), any(), same(callback), any(CancellationToken.class)))
                .thenReturn(false);
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("抖音是什么", List.of("抖音是什么")));
        IntentNode webSearchNode = IntentNode.builder()
                .id("web-search-general")
                .kind(IntentKind.MCP)
                .mcpToolId("web_search")
                .build();
        when(intentResolver.resolve(any(RewriteResult.class), any(CancellationToken.class)))
                .thenReturn(List.of(new SubQuestionIntent(
                        "抖音是什么",
                        List.of(NodeScore.builder().node(webSearchNode).score(0.93D).build())
                )));
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(agentModeDecider.decide(anyString(), anyList(), any())).thenReturn(AgentModeDecision.disabled("test"));
        when(retrievalEngine.retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class)))
                .thenReturn(RetrievalContext.builder()
                        .kbContext("")
                        .mcpContext("")
                        .intentChunks(java.util.Map.of())
                        .build());
        when(promptTemplateLoader.load(anyString())).thenReturn("system prompt");
        when(llmService.streamChat(any(ChatRequest.class), any(StreamCallback.class))).thenReturn(handle);

        ragChatService.streamChat("抖音是什么", null, false, new SseEmitter(0L));

        verify(callback).onContent(contains("联网检索暂未命中可靠结果"));
        verify(llmService).streamChat(requestCaptor.capture(), any(StreamCallback.class));
        verify(taskManager).bindHandle(anyString(), same(handle));
        String fallbackPrompt = requestCaptor.getValue().getMessages().get(1).getContent();
        assertTrue(fallbackPrompt.contains("不要主动添加时效免责声明"));
        assertTrue(fallbackPrompt.contains("问题：抖音是什么"));
    }

    private void mockMemoryPlan() {
        ConversationMemorySnapshot snapshot = ConversationMemorySnapshot.builder()
                .recentHistory(List.of(ChatMessage.user("history")))
                .build();
        ConversationMemoryPlan plan = ConversationMemoryPlan.builder()
                .rewriteHistory(List.of(ChatMessage.user("history")))
                .answerHistory(List.of(ChatMessage.user("history")))
                .historyTokens(20)
                .summaryTokens(0)
                .recentTurnsKept(1)
                .summaryIncluded(false)
                .retrievalTopK(8)
                .retrievalBudgetTokens(4000)
                .rewriteHistoryTokens(20)
                .rewriteSummaryIncluded(false)
                .build();
        when(memoryService.loadSnapshot(anyString(), any())).thenReturn(snapshot);
        when(memoryService.append(anyString(), any(), any())).thenReturn(1L);
        when(memoryPlanner.plan(any(ConversationMemorySnapshot.class), anyString())).thenReturn(plan);
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
