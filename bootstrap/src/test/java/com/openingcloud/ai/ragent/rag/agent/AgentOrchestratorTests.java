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

package com.openingcloud.ai.ragent.rag.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.openingcloud.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.openingcloud.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.openingcloud.ai.ragent.rag.core.rewrite.RewriteResult;
import com.openingcloud.ai.ragent.rag.dto.AgentConfirmPayload;
import com.openingcloud.ai.ragent.rag.dto.AgentObservePayload;
import com.openingcloud.ai.ragent.rag.dto.AgentPlanPayload;
import com.openingcloud.ai.ragent.rag.dto.AgentStepPayload;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTests {

    @Mock
    private LLMService llmService;

    @Mock
    private QueryRewriteService queryRewriteService;

    @Mock
    private IntentResolver intentResolver;

    @Mock
    private RetrievalEngine retrievalEngine;

    @Mock
    private MCPService mcpService;

    @Mock
    private MCPToolRegistry mcpToolRegistry;

    @Mock
    private MCPParameterExtractor mcpParameterExtractor;

    @Mock
    private PendingProposalStore pendingProposalStore;

    @Mock
    private StreamCallback callback;

    @Mock
    private MCPToolExecutor toolExecutor;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setAgentEnabled(true);
        properties.setAgentMaxLoops(2);
        properties.setAgentMaxStepsPerLoop(2);
        properties.setChatKbTemperature(0.3D);
        properties.setChatKbTopP(0.85D);
        properties.setChatMaxTokensKb(1024);
        orchestrator = new AgentOrchestrator(
                llmService,
                properties,
                queryRewriteService,
                intentResolver,
                retrievalEngine,
                mcpService,
                mcpToolRegistry,
                mcpParameterExtractor,
                pendingProposalStore,
                new ObjectMapper()
        );
    }

    @Test
    void shouldRunObserveReasonActLoopBeforeAnswering() {
        IntentNode kbNode = IntentNode.builder()
                .id("kb-aqs")
                .kind(IntentKind.KB)
                .build();
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"回答 AQS 原理",
                  "thought":"当前 observation 为空，需要先补知识库证据。",
                  "done":false,
                  "action":{
                    "type":"KB_RETRIEVE",
                    "instruction":"检索 AQS 资料",
                    "query":"AQS 原理"
                  }
                }
                """,
                """
                {
                  "goal":"回答 AQS 原理",
                  "thought":"已观察到足够的 KB 证据，先汇总出回答骨架。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"先汇总 AQS 的关键点",
                    "query":"AQS 原理"
                  }
                }
                """,
                "AQS 总结骨架",
                """
                {
                  "goal":"回答 AQS 原理",
                  "thought":"已有汇总结果，继续提炼成最终回答。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"继续汇总 AQS 的关键点和回答稿",
                    "query":"AQS 原理"
                  }
                }
                """,
                "这是最终回答"
        );
        when(queryRewriteService.rewriteWithSplit(eq("AQS 原理"), anyList()))
                .thenReturn(new RewriteResult("AQS 原理", List.of("AQS 原理")));
        when(intentResolver.resolve(any(RewriteResult.class), same(CancellationToken.NONE)))
                .thenReturn(List.of(new SubQuestionIntent(
                        "AQS 原理",
                        List.of(NodeScore.builder().node(kbNode).score(0.92D).build())
                )));
        when(retrievalEngine.retrieve(anyList(), eq(DEFAULT_TOP_K), same(CancellationToken.NONE)))
                .thenReturn(RetrievalContext.builder()
                        .kbContext("AQS 是一个基于 CLH 变体的同步队列，用于协调线程获取锁。")
                        .intentChunks(Map.of())
                        .build());

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("AQS 原理")
                .conversationId("c1")
                .userId("u1")
                .history(List.of(ChatMessage.user("AQS 原理")))
                .subIntents(List.of(new SubQuestionIntent("AQS 原理", List.of(NodeScore.builder().node(kbNode).score(0.92D).build())))
                )
                .firstRoundContext(RetrievalContext.builder().kbContext("").mcpContext("").intentChunks(Map.of()).build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("这是最终回答");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentObservePayload.class::isInstance));
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentPlanPayload.class::isInstance));
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentStepPayload.class::isInstance));
    }

    @Test
    void shouldForceFinalAnswerWhenReasonerBacktracksAfterSynthesize() {
        IntentNode kbNode = IntentNode.builder()
                .id("kb-spring")
                .kind(IntentKind.KB)
                .build();
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"制定 Spring 复习计划",
                  "thought":"需要先补充 Spring 主题的知识库证据。",
                  "done":false,
                  "action":{
                    "type":"KB_RETRIEVE",
                    "instruction":"检索 Spring 复习资料",
                    "query":"Spring 复习计划"
                  }
                }
                """,
                """
                {
                  "goal":"制定 Spring 复习计划",
                  "thought":"已有知识库证据，先汇总成计划骨架。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"汇总 Spring 复习计划",
                    "query":"Spring 复习计划"
                  }
                }
                """,
                "Spring 复习计划骨架",
                """
                {
                  "goal":"制定 Spring 复习计划",
                  "thought":"AOP 主题似乎还可以再补一轮检索。",
                  "done":false,
                  "action":{
                    "type":"KB_RETRIEVE",
                    "instruction":"继续检索 AOP 复习计划建议",
                    "query":"AOP 复习计划建议"
                  }
                }
                """,
                "这是复习计划最终答案"
        );
        when(queryRewriteService.rewriteWithSplit(eq("Spring 复习计划"), anyList()))
                .thenReturn(new RewriteResult("Spring 复习计划", List.of("Spring 复习计划")));
        when(intentResolver.resolve(any(RewriteResult.class), same(CancellationToken.NONE)))
                .thenReturn(List.of(new SubQuestionIntent(
                        "Spring 复习计划",
                        List.of(NodeScore.builder().node(kbNode).score(0.92D).build())
                )));
        when(retrievalEngine.retrieve(anyList(), eq(DEFAULT_TOP_K), same(CancellationToken.NONE)))
                .thenReturn(RetrievalContext.builder()
                        .kbContext("Spring Bean 生命周期、AOP 与事务失效是高频复习主题。")
                        .intentChunks(Map.of())
                        .build());

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("Spring 复习计划")
                .conversationId("c2")
                .userId("u2")
                .history(List.of(ChatMessage.user("Spring 复习计划")))
                .subIntents(List.of(new SubQuestionIntent("Spring 复习计划", List.of(NodeScore.builder().node(kbNode).score(0.92D).build())))
                )
                .firstRoundContext(RetrievalContext.builder().kbContext("").mcpContext("").intentChunks(Map.of()).build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("这是复习计划最终答案");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentObservePayload.class::isInstance));
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentPlanPayload.class::isInstance));
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentStepPayload.class::isInstance));
    }

    @Test
    void shouldCreatePendingProposalForWriteAction() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"创建今天的笔记",
                  "thought":"这是明确的写入目标，需要先准备写工具调用。",
                  "done":false,
                  "action":{
                    "type":"MCP_CALL",
                    "instruction":"创建今日日报",
                    "query":"帮我创建今天的笔记",
                    "toolId":"obsidian_create",
                    "params":{
                      "name":"今日日报"
                    }
                  }
                }
                """
        );

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_create")
                .name("创建笔记")
                .parameters(Map.of(
                        "name",
                        MCPTool.ParameterDef.builder()
                                .description("笔记名")
                                .required(true)
                                .build()
                ))
                .build();
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-1")
                .toolId("obsidian_create")
                .conversationId("c1")
                .userId("u1")
                .parameters(new HashMap<>(Map.of("name", "今日日报")))
                .targetPath("Daily/今日日报.md")
                .riskHint("写操作默认需要人工确认，防止误写入")
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 60_000)
                .build();

        when(mcpToolRegistry.getExecutor(anyString())).thenReturn(Optional.empty());
        when(mcpToolRegistry.getExecutor("obsidian_create")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.validate(anyMap(), same(tool)))
                .thenAnswer(invocation -> MCPParameterExtractor.ParameterValidationResult.valid(invocation.getArgument(0)));
        when(pendingProposalStore.create(anyString(), anyString(), any(MCPRequest.class), nullable(String.class), anyString()))
                .thenReturn(proposal);

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("帮我创建今天的笔记")
                .conversationId("c1")
                .userId("u1")
                .history(List.of(ChatMessage.user("帮我创建今天的笔记")))
                .subIntents(List.of())
                .firstRoundContext(RetrievalContext.builder().kbContext("").mcpContext("").intentChunks(Map.of()).build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("检测到写操作，需要你确认后才会执行。\n请输入 `/confirm p-1` 执行，或 `/reject p-1` 取消。");
        verify(callback).onComplete();
        verify(mcpService, never()).execute(any(MCPRequest.class));
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentConfirmPayload.class::isInstance));
    }

    @Test
    void shouldNormalizeHallucinatedWriteToolId() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"创建检查清单笔记",
                  "thought":"需要直接调用写工具创建笔记。",
                  "done":false,
                  "action":{
                    "type":"MCP_CALL",
                    "instruction":"创建《数据脱敏检查清单》笔记",
                    "query":"创建《数据脱敏检查清单》笔记",
                    "toolId":"note-creation-tool",
                    "params":{
                      "name":"数据脱敏检查清单"
                    }
                  }
                }
                """
        );

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_create")
                .name("创建笔记")
                .parameters(Map.of(
                        "name",
                        MCPTool.ParameterDef.builder()
                                .description("笔记名")
                                .required(true)
                                .build()
                ))
                .build();
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-2")
                .toolId("obsidian_create")
                .conversationId("c2")
                .userId("u2")
                .parameters(new HashMap<>(Map.of("name", "数据脱敏检查清单")))
                .targetPath("Notes/数据脱敏检查清单.md")
                .riskHint("写操作默认需要人工确认，防止误写入")
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 60_000)
                .build();

        when(mcpToolRegistry.getExecutor(anyString())).thenReturn(Optional.empty());
        when(mcpToolRegistry.getExecutor("obsidian_create")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.validate(anyMap(), same(tool)))
                .thenAnswer(invocation -> MCPParameterExtractor.ParameterValidationResult.valid(invocation.getArgument(0)));
        when(pendingProposalStore.create(anyString(), anyString(), any(MCPRequest.class), nullable(String.class), anyString()))
                .thenReturn(proposal);

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("创建《数据脱敏检查清单》笔记")
                .conversationId("c2")
                .userId("u2")
                .history(List.of(ChatMessage.user("创建《数据脱敏检查清单》笔记")))
                .subIntents(List.of())
                .firstRoundContext(RetrievalContext.builder().kbContext("").mcpContext("").intentChunks(Map.of()).build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("检测到写操作，需要你确认后才会执行。\n请输入 `/confirm p-2` 执行，或 `/reject p-2` 取消。");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentConfirmPayload.class::isInstance));
    }

    @Test
    void shouldForceWriteActionAfterSynthesizeForNoteRequests() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"整理并写入检查清单",
                  "thought":"先根据 observation 汇总出检查清单草稿。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"汇总数据脱敏检查清单",
                    "query":"数据脱敏检查清单"
                  }
                }
                """,
                "脱敏检查清单草稿",
                """
                {
                  "goal":"整理并写入检查清单",
                  "thought":"还可以继续润色检查清单内容。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"继续润色检查清单内容",
                    "query":"数据脱敏检查清单"
                  }
                }
                """
        );

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_create")
                .name("创建笔记")
                .parameters(Map.of(
                        "name",
                        MCPTool.ParameterDef.builder()
                                .description("笔记名")
                                .required(true)
                                .build()
                ))
                .build();
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-3")
                .toolId("obsidian_create")
                .conversationId("c3")
                .userId("u3")
                .parameters(new HashMap<>(Map.of("name", "数据脱敏检查清单")))
                .targetPath("Notes/数据脱敏检查清单.md")
                .riskHint("写操作默认需要人工确认，防止误写入")
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 60_000)
                .build();

        when(mcpToolRegistry.getExecutor("obsidian_create")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq("创建《数据脱敏检查清单》笔记"), same(tool), nullable(String.class)))
                .thenReturn(new HashMap<>(Map.of("name", "数据脱敏检查清单")));
        when(mcpParameterExtractor.validate(anyMap(), same(tool)))
                .thenAnswer(invocation -> MCPParameterExtractor.ParameterValidationResult.valid(invocation.getArgument(0)));
        when(pendingProposalStore.create(anyString(), anyString(), any(MCPRequest.class), nullable(String.class), anyString()))
                .thenReturn(proposal);

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("创建《数据脱敏检查清单》笔记")
                .conversationId("c3")
                .userId("u3")
                .history(List.of(ChatMessage.user("创建《数据脱敏检查清单》笔记")))
                .subIntents(List.of())
                .firstRoundContext(RetrievalContext.builder().kbContext("已有检查清单草稿").mcpContext("").intentChunks(Map.of()).build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("检测到写操作，需要你确认后才会执行。\n请输入 `/confirm p-3` 执行，或 `/reject p-3` 取消。");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream().anyMatch(AgentConfirmPayload.class::isInstance));
    }

    @Test
    void shouldRequireConfirmForNaturalLanguageDailyAppendAfterSynthesize() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"整理灵感并写入今日日记",
                  "thought":"已有知识库证据，先汇总成一条适合写入日记的灵感。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"汇总可写入日记的灵感",
                    "query":"帮我加一条灵感到我的今日日记里，多去用感官感受"
                  }
                }
                """,
                "多去用感官感受，记录当下身体和环境的细节。",
                """
                {
                  "goal":"整理灵感并写入今日日记",
                  "thought":"灵感已经汇总完成，可以继续整理一下表达。",
                  "done":false,
                  "action":{
                    "type":"SYNTHESIZE",
                    "instruction":"继续整理日记表达",
                    "query":"帮我加一条灵感到我的今日日记里，多去用感官感受"
                  }
                }
                """
        );

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_update")
                .name("更新笔记")
                .parameters(Map.of(
                        "content",
                        MCPTool.ParameterDef.builder()
                                .description("写入内容")
                                .required(true)
                                .build(),
                        "daily",
                        MCPTool.ParameterDef.builder()
                                .description("是否写入日记")
                                .enumValues(List.of("true", "false"))
                                .defaultValue("false")
                                .build()
                ))
                .build();
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-daily")
                .toolId("obsidian_update")
                .conversationId("c-daily")
                .userId("u-daily")
                .parameters(new HashMap<>(Map.of(
                        "daily", "true",
                        "content", "多去用感官感受，记录当下身体和环境的细节。"
                )))
                .targetPath("2-Resource（参考资源）/80_生活记录/DailyNote/日记/" + java.time.LocalDate.now() + ".md")
                .riskHint("写操作默认需要人工确认，防止误写入")
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 60_000)
                .build();

        when(mcpToolRegistry.getExecutor("obsidian_update")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq("帮我加一条灵感到我的今日日记里，多去用感官感受"), same(tool), nullable(String.class)))
                .thenReturn(new HashMap<>(Map.of(
                        "daily", "true",
                        "content", "多去用感官感受，记录当下身体和环境的细节。"
                )));
        when(mcpParameterExtractor.validate(anyMap(), same(tool)))
                .thenAnswer(invocation -> MCPParameterExtractor.ParameterValidationResult.valid(invocation.getArgument(0)));
        when(pendingProposalStore.create(anyString(), anyString(), any(MCPRequest.class), nullable(String.class), anyString()))
                .thenReturn(proposal);

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("帮我加一条灵感到我的今日日记里，多去用感官感受")
                .conversationId("c-daily")
                .userId("u-daily")
                .history(List.of(ChatMessage.user("帮我加一条灵感到我的今日日记里，多去用感官感受")))
                .subIntents(List.of())
                .firstRoundContext(RetrievalContext.builder()
                        .kbContext("日记灵感：写作时多记录气味、触感、声音和光线变化。")
                        .mcpContext("")
                        .intentChunks(Map.of())
                        .build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("检测到写操作，需要你确认后才会执行。\n请输入 `/confirm p-daily` 执行，或 `/reject p-daily` 取消。");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream()
                .filter(AgentConfirmPayload.class::isInstance)
                .map(AgentConfirmPayload.class::cast)
                .anyMatch(payload -> "obsidian_update".equals(payload.toolId())));
    }

    @Test
    void shouldFallbackToGlobalKbRetrieveWhenNoKbIntentResolved() {
        IntentNode mcpNode = IntentNode.builder()
                .id("news-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
                .build();
        when(llmService.chat(any(ChatRequest.class))).thenReturn(
                """
                {
                  "goal":"总结脱敏要求",
                  "thought":"先检索知识库证据。",
                  "done":false,
                  "action":{
                    "type":"KB_RETRIEVE",
                    "instruction":"检索脱敏要求",
                    "query":"公司敏感字段脱敏要求"
                  }
                }
                """,
                """
                {
                  "goal":"总结脱敏要求",
                  "thought":"已经拿到 KB 证据，可以直接给结论。",
                  "done":true,
                  "finalAnswer":"敏感字段需要按规范脱敏后再输出。"
                }
                """,
                "敏感字段需要按规范脱敏后再输出。"
        );
        when(queryRewriteService.rewriteWithSplit(eq("公司敏感字段脱敏要求"), anyList()))
                .thenReturn(new RewriteResult("公司敏感字段脱敏要求", List.of("公司敏感字段脱敏要求")));
        when(intentResolver.resolve(any(RewriteResult.class), same(CancellationToken.NONE)))
                .thenReturn(List.of(new SubQuestionIntent(
                        "公司敏感字段脱敏要求",
                        List.of(NodeScore.builder().node(mcpNode).score(0.91D).build())
                )));
        when(retrievalEngine.retrieve(
                argThat(subIntents -> subIntents.size() == 1
                        && "公司敏感字段脱敏要求".equals(subIntents.get(0).subQuestion())
                        && subIntents.get(0).nodeScores().isEmpty()),
                eq(DEFAULT_TOP_K),
                same(CancellationToken.NONE)
        )).thenReturn(RetrievalContext.builder()
                .kbContext("敏感字段展示前必须完成脱敏处理。")
                .intentChunks(Map.of())
                .build());

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean handled = orchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                .question("请总结公司敏感字段脱敏要求")
                .conversationId("c-fallback")
                .userId("u-fallback")
                .history(List.of(ChatMessage.user("请总结公司敏感字段脱敏要求")))
                .subIntents(List.of(new SubQuestionIntent("请总结公司敏感字段脱敏要求",
                        List.of(NodeScore.builder().node(mcpNode).score(0.91D).build()))))
                .firstRoundContext(RetrievalContext.builder().kbContext("").mcpContext("").intentChunks(Map.of()).build())
                .emitter(emitter)
                .callback(callback)
                .token(CancellationToken.NONE)
                .build());

        assertTrue(handled);
        verify(callback).onContent("敏感字段需要按规范脱敏后再输出。");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream()
                .filter(AgentStepPayload.class::isInstance)
                .map(AgentStepPayload.class::cast)
                .anyMatch(payload -> "KB_RETRIEVE".equals(payload.type()) && "SUCCESS".equals(payload.status())));
    }

    private static final class CapturingSseEmitter extends SseEmitter {

        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> rawPayloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            eventNames.add(extractEventName(parts));
            for (ResponseBodyEmitter.DataWithMediaType each : parts) {
                rawPayloads.add(each.getData());
            }
        }

        private String extractEventName(Set<ResponseBodyEmitter.DataWithMediaType> parts) {
            for (ResponseBodyEmitter.DataWithMediaType each : parts) {
                Object data = each.getData();
                if (data instanceof String line && line.startsWith("event:")) {
                    return line.substring("event:".length()).trim();
                }
            }
            return "";
        }
    }
}
