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

import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import com.openingcloud.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillToolExecutorTests {

    @Mock
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;

    @Mock
    private MCPService mcpService;

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    private SkillToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SkillToolExecutor(multiChannelRetrievalEngine, mcpService, knowledgeDocumentMapper);
    }

    @Test
    void shouldReturnErrorForBlankToolName() {
        SkillToolExecutor.ToolExecutionResult result = executor.execute("", Map.of(), CancellationToken.NONE);

        assertFalse(result.success());
        assertTrue(result.content().contains("工具名称为空"));
    }

    @Test
    void shouldExecuteSearchKbAndFilterByKbId() {
        RetrievedChunk matchChunk = new RetrievedChunk();
        matchChunk.setKbId("100");
        matchChunk.setText("Spring AOP 使用动态代理实现");
        matchChunk.setScore(0.92F);

        RetrievedChunk otherChunk = new RetrievedChunk();
        otherChunk.setKbId("200");
        otherChunk.setText("Redis 缓存策略");
        otherChunk.setScore(0.8F);

        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(5), eq(CancellationToken.NONE)))
                .thenReturn(List.of(matchChunk, otherChunk));

        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "search_kb",
                Map.of("kb_id", "100", "query", "Spring AOP", "top_k", 5),
                CancellationToken.NONE
        );

        assertTrue(result.success());
        assertTrue(result.content().contains("Spring AOP 使用动态代理实现"));
        assertFalse(result.content().contains("Redis 缓存策略"));
    }

    @Test
    void shouldReturnErrorWhenSearchKbMissingQuery() {
        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "search_kb",
                Map.of("kb_id", "100"),
                CancellationToken.NONE
        );

        assertFalse(result.success());
        assertTrue(result.content().contains("query"));
    }

    @Test
    void shouldExecuteSearchAll() {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setKbId("100");
        chunk.setText("AQS 是基于 CLH 队列的同步框架");
        chunk.setScore(0.95F);

        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(anyList(), eq(5), eq(CancellationToken.NONE)))
                .thenReturn(List.of(chunk));

        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "search_all",
                Map.of("query", "AQS 原理"),
                CancellationToken.NONE
        );

        assertTrue(result.success());
        assertTrue(result.content().contains("AQS 是基于 CLH 队列的同步框架"));
    }

    @Test
    void shouldGetDocumentDetail() {
        KnowledgeDocumentDO doc = new KnowledgeDocumentDO();
        doc.setDocName("Spring面试题.md");
        doc.setFileType("md");
        doc.setSourceLocation("本地上传");
        doc.setChunkCount(15);

        when(knowledgeDocumentMapper.selectById(42L)).thenReturn(doc);

        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "get_document_detail",
                Map.of("document_id", "42"),
                CancellationToken.NONE
        );

        assertTrue(result.success());
        assertTrue(result.content().contains("Spring面试题.md"));
        assertTrue(result.content().contains("15"));
    }

    @Test
    void shouldReturnErrorForNonexistentDocument() {
        when(knowledgeDocumentMapper.selectById(999L)).thenReturn(null);

        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "get_document_detail",
                Map.of("document_id", "999"),
                CancellationToken.NONE
        );

        assertFalse(result.success());
        assertTrue(result.content().contains("文档不存在"));
    }

    @Test
    void shouldDelegateToMcpService() {
        when(mcpService.isToolAvailable("web_search")).thenReturn(true);
        MCPResponse response = MCPResponse.builder()
                .success(true)
                .textResult("搜索结果内容")
                .build();
        when(mcpService.execute(any())).thenReturn(response);

        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "web_search",
                Map.of("query", "天气"),
                CancellationToken.NONE
        );

        assertTrue(result.success());
        assertTrue(result.content().contains("搜索结果内容"));
    }

    @Test
    void shouldReturnErrorForUnknownTool() {
        when(mcpService.isToolAvailable("unknown_tool")).thenReturn(false);

        SkillToolExecutor.ToolExecutionResult result = executor.execute(
                "unknown_tool",
                Map.of(),
                CancellationToken.NONE
        );

        assertFalse(result.success());
        assertTrue(result.content().contains("未知工具"));
    }
}
