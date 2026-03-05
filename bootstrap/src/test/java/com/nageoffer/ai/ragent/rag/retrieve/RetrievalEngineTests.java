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

package com.nageoffer.ai.ragent.rag.retrieve;

import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalEngineTests {

    @Mock
    private ContextFormatter contextFormatter;
    @Mock
    private MCPService mcpService;
    @Mock
    private MCPParameterExtractor mcpParameterExtractor;
    @Mock
    private MCPToolRegistry mcpToolRegistry;
    @Mock
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    @Mock
    private MCPToolExecutor mcpToolExecutor;

    private RetrievalEngine retrievalEngine;
    private final Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        retrievalEngine = new RetrievalEngine(
                contextFormatter,
                mcpService,
                mcpParameterExtractor,
                mcpToolRegistry,
                multiChannelRetrievalEngine,
                directExecutor
        );
    }

    @Test
    void testMcpOnlyIntentShouldSkipKbRetrieval() {
        String question = "华东区这个月销售额多少？";
        IntentNode mcpNode = IntentNode.builder()
                .id("sales-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("sales_query")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(NodeScore.builder().node(mcpNode).score(0.95D).build())
        );

        MCPTool tool = MCPTool.builder()
                .toolId("sales_query")
                .name("销售查询")
                .description("查询销售数据")
                .parameters(Map.of(
                        "region",
                        MCPTool.ParameterDef.builder().type("string").required(false).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("sales_query")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), isNull()))
                .thenReturn(Map.of("region", "华东"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.success("sales_query", "华东区本月销售额为 358.63 万元")));
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasMcp());
        assertFalse(context.hasKb());
        assertTrue(context.getMcpContext().contains("mcp-context"));
        verify(multiChannelRetrievalEngine, never()).retrieveKnowledgeChannels(anyList(), anyInt(),
                any(CancellationToken.class));
        verify(mcpService).executeBatch(argThat(requests ->
                        requests.size() == 1
                                && "sales_query".equals(requests.get(0).getToolId())
                                && "华东".equals(requests.get(0).getParameter("region"))),
                any(CancellationToken.class));
    }

    @Test
    void testLowConfidenceMcpIntentShouldFallbackToKbRetrieval() {
        String question = "公司数据安全规范里，敏感字段脱敏要求是什么？";
        IntentNode mcpNode = IntentNode.builder()
                .id("sales-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("sales_query")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(NodeScore.builder().node(mcpNode).score(0.45D).build())
        );

        RetrievedChunk kbChunk = RetrievedChunk.builder()
                .id("chunk-1")
                .text("敏感字段必须脱敏后展示。")
                .score(0.78F)
                .build();
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(5), any(CancellationToken.class)))
                .thenReturn(List.of(kbChunk));
        when(contextFormatter.formatKbContext(anyList(), anyMap(), eq(5))).thenReturn("kb-fallback-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasKb());
        assertFalse(context.hasMcp());
        assertTrue(context.getKbContext().contains("kb-fallback-context"));
        verify(multiChannelRetrievalEngine).retrieveKnowledgeChannels(anyList(), eq(5), any(CancellationToken.class));
        verify(mcpService, never()).executeBatch(anyList(), any(CancellationToken.class));
    }

    @Test
    void testMixedIntentShouldMergeKbAndMcpContext() {
        String question = "华东区这个月销售额多少，并说明销售口径定义";
        IntentNode kbNode = IntentNode.builder()
                .id("kb-sales-policy")
                .kind(IntentKind.KB)
                .build();
        IntentNode mcpNode = IntentNode.builder()
                .id("sales-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("sales_query")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(
                        NodeScore.builder().node(kbNode).score(0.88D).build(),
                        NodeScore.builder().node(mcpNode).score(0.92D).build()
                )
        );

        RetrievedChunk kbChunk = RetrievedChunk.builder()
                .id("chunk-1")
                .text("销售口径说明：以合同签署金额为准。")
                .score(0.9F)
                .build();
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(6), any(CancellationToken.class)))
                .thenReturn(List.of(kbChunk));
        when(contextFormatter.formatKbContext(anyList(), anyMap(), eq(6))).thenReturn("kb-context");

        MCPTool tool = MCPTool.builder()
                .toolId("sales_query")
                .name("销售查询")
                .description("查询销售数据")
                .parameters(Map.of(
                        "period",
                        MCPTool.ParameterDef.builder().type("string").required(false).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("sales_query")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), isNull()))
                .thenReturn(Map.of("period", "本月"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.success("sales_query", "本月销售额 358.63 万元")));
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 6, CancellationToken.NONE);

        assertTrue(context.hasKb());
        assertTrue(context.hasMcp());
        assertTrue(context.getKbContext().contains("kb-context"));
        assertTrue(context.getMcpContext().contains("mcp-context"));
        verify(multiChannelRetrievalEngine).retrieveKnowledgeChannels(anyList(), eq(6), any(CancellationToken.class));
        verify(mcpService).executeBatch(anyList(), any(CancellationToken.class));
    }

    @Test
    void testMcpFailureResponseShouldStillBuildMcpContext() {
        String question = "帮我写日记";
        IntentNode mcpNode = IntentNode.builder()
                .id("obs-update-daily")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(NodeScore.builder().node(mcpNode).score(0.92D).build())
        );

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_update")
                .name("更新 Obsidian")
                .description("更新 Obsidian")
                .parameters(Map.of(
                        "content",
                        MCPTool.ParameterDef.builder().type("string").required(true).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("obsidian_update")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), isNull()))
                .thenReturn(Map.of("content", "测试内容"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.error("obsidian_update", "DATE_CONFLICT", "冲突")));
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp-error-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasMcp());
        assertTrue(context.getMcpContext().contains("mcp-error-context"));
    }

    @Test
    void testMcpRequestsShouldDeduplicateByToolIdAndKeepHighestScore() {
        String question = "更新今日日记";
        IntentNode highNode = IntentNode.builder()
                .id("obs-update-daily")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .paramPromptTemplate("HIGH_PROMPT")
                .build();
        IntentNode lowNode = IntentNode.builder()
                .id("obs-update-append")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .paramPromptTemplate("LOW_PROMPT")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(
                        NodeScore.builder().node(highNode).score(0.96D).build(),
                        NodeScore.builder().node(lowNode).score(0.83D).build()
                )
        );

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_update")
                .name("更新 Obsidian")
                .description("更新 Obsidian")
                .parameters(Map.of(
                        "content",
                        MCPTool.ParameterDef.builder().type("string").required(true).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("obsidian_update")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), eq("HIGH_PROMPT")))
                .thenReturn(Map.of("content", "high"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.success("obsidian_update", "ok")));
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasMcp());
        verify(mcpParameterExtractor, never()).extractParameters(eq(question), same(tool), eq("LOW_PROMPT"));
        verify(mcpService).executeBatch(argThat(requests ->
                        requests.size() == 1
                                && "obsidian_update".equals(requests.get(0).getToolId())
                                && "high".equals(requests.get(0).getParameter("content"))),
                any(CancellationToken.class));
    }
}
