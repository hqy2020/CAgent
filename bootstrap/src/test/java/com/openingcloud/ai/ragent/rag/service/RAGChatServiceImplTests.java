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
import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
import com.openingcloud.ai.ragent.rag.core.intent.IntentRouter;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.core.intent.RoutingDecision;
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
    private ConversationMemoryPlanner memoryPlanner;
    @Mock
    private RAGConfigProperties ragConfigProperties;
    @Mock
    private StreamTaskManager taskManager;
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
    private IntentRouter intentRouter;
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
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("改写问题",
                List.of(NodeScore.builder().node(kbNode).score(0.9D).build())));
        when(intentRouter.route(anyString(), any(CancellationToken.class)))
                .thenReturn(RoutingDecision.knowledge(subIntents));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", "改写问题", List.of("改写问题")));
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
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("改写问题", List.of()));
        when(intentRouter.route(anyString(), any(CancellationToken.class)))
                .thenReturn(RoutingDecision.knowledge(subIntents));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", "改写问题", List.of("改写问题")));
        when(retrievalEngine.retrieve(anyList(), anyInt(), anyInt(), any(), any(CancellationToken.class))).thenThrow(new RuntimeException("retrieval down"));
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
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("改写问题",
                List.of(NodeScore.builder().node(kbNode).score(0.9D).build())));
        when(intentRouter.route(anyString(), any(CancellationToken.class)))
                .thenReturn(RoutingDecision.knowledge(subIntents));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult("改写问题", "改写问题", List.of("改写问题")));
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

    private void mockMemoryPlan() {
        mockMemoryPlan(List.of(ChatMessage.user("history")));
    }

    private void mockMemoryPlan(List<ChatMessage> answerHistory) {
        ConversationMemorySnapshot snapshot = ConversationMemorySnapshot.builder()
                .recentHistory(answerHistory)
                .build();
        ConversationMemoryPlan plan = ConversationMemoryPlan.builder()
                .rewriteHistory(answerHistory)
                .answerHistory(answerHistory)
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
