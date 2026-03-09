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
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    @Mock
    private TokenCounterService tokenCounterService;
    @Mock
    private RagTraceRecordService ragTraceRecordService;

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
                tokenCounterService,
                ragTraceRecordService,
                directExecutor
        );
        lenient().when(tokenCounterService.countTokens(anyString())).thenReturn(10);
    }

    @Test
    void testMcpOnlyIntentShouldSkipKbRetrieval() {
        String question = "帮我联网搜索今天 AI 领域的 3 条新闻";
        IntentNode mcpNode = IntentNode.builder()
                .id("news-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(NodeScore.builder().node(mcpNode).score(0.95D).build())
        );

        MCPTool tool = MCPTool.builder()
                .toolId("web_news_search")
                .name("联网新闻搜索")
                .description("联网搜索近期新闻")
                .parameters(Map.of(
                        "query",
                        MCPTool.ParameterDef.builder().type("string").required(true).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("web_news_search")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), isNull()))
                .thenReturn(Map.of("query", "AI 领域"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.success("web_news_search", "今天 AI 领域新闻 3 条")));
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasMcp());
        assertFalse(context.hasKb());
        assertTrue(context.getMcpContext().contains("mcp-context"));
        verify(multiChannelRetrievalEngine, never()).retrieveKnowledgeChannels(anyList(), anyInt(),
                any(CancellationToken.class));
        verify(mcpService).executeBatch(argThat(requests ->
                        requests.size() == 1
                                && "web_news_search".equals(requests.get(0).getToolId())
                                && "AI 领域".equals(requests.get(0).getParameter("query"))),
                any(CancellationToken.class));
    }

    @Test
    void testLowConfidenceMcpIntentShouldFallbackToKbRetrieval() {
        String question = "公司数据安全规范里，敏感字段脱敏要求是什么？";
        IntentNode mcpNode = IntentNode.builder()
                .id("news-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
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
        when(contextFormatter.formatKbContext(anyList(), anyMap(), eq(5), anyInt())).thenReturn("kb-fallback-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasKb());
        assertFalse(context.hasMcp());
        assertTrue(context.getKbContext().contains("kb-fallback-context"));
        verify(multiChannelRetrievalEngine).retrieveKnowledgeChannels(anyList(), eq(5), any(CancellationToken.class));
        verify(mcpService, never()).executeBatch(anyList(), any(CancellationToken.class));
    }

    @Test
    void testMixedIntentShouldMergeKbAndMcpContext() {
        String question = "帮我联网搜索今天 AI 新闻，并说明公司新闻摘要规范";
        IntentNode kbNode = IntentNode.builder()
                .id("kb-news-policy")
                .kind(IntentKind.KB)
                .build();
        IntentNode mcpNode = IntentNode.builder()
                .id("news-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
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
                .text("公司新闻摘要规范：需要标注来源、发布日期和核心结论。")
                .score(0.9F)
                .build();
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(6), any(CancellationToken.class)))
                .thenReturn(List.of(kbChunk));
        when(contextFormatter.formatKbContext(anyList(), anyMap(), eq(6), anyInt())).thenReturn("kb-context");

        MCPTool tool = MCPTool.builder()
                .toolId("web_news_search")
                .name("联网新闻搜索")
                .description("联网搜索近期新闻")
                .parameters(Map.of(
                        "query",
                        MCPTool.ParameterDef.builder().type("string").required(true).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("web_news_search")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), isNull()))
                .thenReturn(Map.of("query", "AI 新闻"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.success("web_news_search", "今天 AI 新闻 3 条")));
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
    void testWriteMcpIntentShouldNotExecuteAndShouldFallbackToKb() {
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

        RetrievedChunk kbChunk = RetrievedChunk.builder()
                .id("chunk-1")
                .text("日记模板与规范说明")
                .score(0.81F)
                .build();
        MCPTool writeTool = MCPTool.builder()
                .toolId("obsidian_update")
                .name("写 Obsidian")
                .operationType(MCPTool.OperationType.WRITE)
                .build();
        when(mcpToolRegistry.getExecutor("obsidian_update")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(writeTool);
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(5), any(CancellationToken.class)))
                .thenReturn(List.of(kbChunk));
        when(contextFormatter.formatKbContext(anyList(), anyMap(), eq(5), anyInt())).thenReturn("kb-fallback-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasKb());
        assertFalse(context.hasMcp());
        assertTrue(context.getKbContext().contains("kb-fallback-context"));
        verify(mcpToolRegistry).getExecutor("obsidian_update");
        verify(mcpService, never()).executeBatch(anyList(), any(CancellationToken.class));
    }

    @Test
    void testIncompatibleReadOnlyMcpIntentsShouldFallbackToKbWithoutExecutingTools() {
        String question = "公司数据安全规范里，敏感字段脱敏要求是什么？";
        IntentNode obsidianNode = IntentNode.builder()
                .id("obsidian-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_search")
                .build();
        IntentNode newsNode = IntentNode.builder()
                .id("news-intent")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
                .build();
        SubQuestionIntent subIntent = new SubQuestionIntent(
                question,
                List.of(
                        NodeScore.builder().node(obsidianNode).score(0.88D).build(),
                        NodeScore.builder().node(newsNode).score(0.83D).build()
                )
        );

        RetrievedChunk kbChunk = RetrievedChunk.builder()
                .id("chunk-1")
                .text("敏感字段必须按规则脱敏。")
                .score(0.82F)
                .build();
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(5), any(CancellationToken.class)))
                .thenReturn(List.of(kbChunk));
        when(contextFormatter.formatKbContext(anyList(), anyMap(), eq(5), anyInt())).thenReturn("kb-security-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasKb());
        assertFalse(context.hasMcp());
        assertTrue(context.getKbContext().contains("kb-security-context"));
        verify(multiChannelRetrievalEngine).retrieveKnowledgeChannels(anyList(), eq(5), any(CancellationToken.class));
        verify(mcpService, never()).executeBatch(anyList(), any(CancellationToken.class));
    }

    @Test
    void testMcpRequestsShouldDeduplicateByToolIdAndKeepHighestScore() {
        String question = "帮我联网搜索今天 AI 新闻";
        IntentNode highNode = IntentNode.builder()
                .id("news-query-high")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
                .paramPromptTemplate("HIGH_PROMPT")
                .build();
        IntentNode lowNode = IntentNode.builder()
                .id("news-query-low")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
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
                .toolId("web_news_search")
                .name("联网新闻搜索")
                .description("联网搜索近期新闻")
                .parameters(Map.of(
                        "query",
                        MCPTool.ParameterDef.builder().type("string").required(true).build()
                ))
                .build();
        when(mcpToolRegistry.getExecutor("web_news_search")).thenReturn(Optional.of(mcpToolExecutor));
        when(mcpToolExecutor.getToolDefinition()).thenReturn(tool);
        when(mcpParameterExtractor.extractParameters(eq(question), same(tool), eq("HIGH_PROMPT")))
                .thenReturn(Map.of("query", "AI 新闻"));
        when(mcpService.executeBatch(anyList(), any(CancellationToken.class)))
                .thenReturn(List.of(MCPResponse.success("web_news_search", "ok")));
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp-context");

        RetrievalContext context = retrievalEngine.retrieve(List.of(subIntent), 5, CancellationToken.NONE);

        assertTrue(context.hasMcp());
        verify(mcpParameterExtractor, never()).extractParameters(eq(question), same(tool), eq("LOW_PROMPT"));
        verify(mcpService).executeBatch(argThat(requests ->
                        requests.size() == 1
                                && "web_news_search".equals(requests.get(0).getToolId())
                                && "AI 新闻".equals(requests.get(0).getParameter("query"))),
                any(CancellationToken.class));
    }
}
