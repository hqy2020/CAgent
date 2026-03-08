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

package com.nageoffer.ai.ragent.rag.intent;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.core.intent.DefaultIntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultIntentClassifierTests {

    @Mock
    private LLMService llmService;
    @Mock
    private IntentNodeMapper intentNodeMapper;
    @Mock
    private PromptTemplateLoader promptTemplateLoader;
    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;
    @Mock
    private MCPToolRegistry mcpToolRegistry;

    private DefaultIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DefaultIntentClassifier(
                llmService,
                intentNodeMapper,
                promptTemplateLoader,
                intentTreeCacheManager,
                mcpToolRegistry
        );

        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("intent prompt");
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(List.of(
                IntentNode.builder()
                        .id("kb-1")
                        .name("知识点一")
                        .description("desc-1")
                        .fullPath("分类 > 知识点一")
                        .build(),
                IntentNode.builder()
                        .id("kb-2")
                        .name("知识点二")
                        .description("desc-2")
                        .fullPath("分类 > 知识点二")
                        .build()
        ));
    }

    @Test
    void shouldParsePlainJsonArray() {
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class))).thenReturn("""
                [
                  {"id":"kb-2","score":0.61},
                  {"id":"kb-1","score":0.93}
                ]
                """);

        List<NodeScore> result = classifier.classifyTargets("事务传播");

        assertEquals(2, result.size());
        assertEquals("kb-1", result.get(0).getNode().getId());
        assertEquals(0.93D, result.get(0).getScore(), 0.0001D);
        assertEquals("kb-2", result.get(1).getNode().getId());
    }

    @Test
    void shouldParseResultsWrapperObject() {
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class))).thenReturn("""
                {
                  "results": [
                    {"id":"kb-1","score":0.87}
                  ]
                }
                """);

        List<NodeScore> result = classifier.classifyTargets("事务失效");

        assertEquals(1, result.size());
        assertEquals("kb-1", result.get(0).getNode().getId());
        assertEquals(0.87D, result.get(0).getScore(), 0.0001D);
    }

    @Test
    void shouldExtractJsonFromFencedResponseWithWrapperText() {
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class))).thenReturn("""
                以下是识别结果：
                ```json
                {"results":[{"id":"kb-2","score":0.88}]}
                ```
                仅供参考。
                """);

        List<NodeScore> result = classifier.classifyTargets("Bean 生命周期");

        assertEquals(1, result.size());
        assertEquals("kb-2", result.get(0).getNode().getId());
        assertEquals(0.88D, result.get(0).getScore(), 0.0001D);
    }

    @Test
    void shouldSalvageClosedObjectsFromTruncatedArray() {
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class))).thenReturn("""
                [{"id":"kb-1","score":0.91},{"id":"kb-2","score":0.67},{"id":"kb-1","score":
                """);

        List<NodeScore> result = classifier.classifyTargets("总结后创建笔记");

        assertEquals(2, result.size());
        assertEquals("kb-1", result.get(0).getNode().getId());
        assertEquals(0.91D, result.get(0).getScore(), 0.0001D);
        assertEquals("kb-2", result.get(1).getNode().getId());
    }

    @Test
    void shouldSkipInvalidEntriesButKeepValidOnes() {
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class))).thenReturn("""
                [
                  {"id":"kb-1","score":0.81},
                  {"id":"kb-missing","score":0.95},
                  {"id":"kb-2"},
                  {"id":"kb-2","score":"oops"},
                  {"id":"kb-2","score":0.61}
                ]
                """);

        List<NodeScore> result = classifier.classifyTargets("数据脱敏");

        assertEquals(2, result.size());
        assertEquals("kb-1", result.get(0).getNode().getId());
        assertEquals("kb-2", result.get(1).getNode().getId());
        assertEquals(0.61D, result.get(1).getScore(), 0.0001D);
    }

    @Test
    void shouldReturnEmptyWhenResponseIsNotRecoverableJson() {
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class)))
                .thenReturn("这次没有可用的 JSON 输出，请直接走降级逻辑。");

        List<NodeScore> result = classifier.classifyTargets("创建日报");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldPruneUnavailableMcpNodesFromCachedIntentTree() {
        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(List.of(
                IntentNode.builder()
                        .id("mcp-1")
                        .name("已下线工具")
                        .description("desc")
                        .fullPath("MCP > 已下线工具")
                        .kind(IntentKind.MCP)
                        .mcpToolId("removed_tool")
                        .build()
        ));
        when(mcpToolRegistry.contains("removed_tool")).thenReturn(false);
        when(llmService.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class)))
                .thenReturn("[{\"id\":\"mcp-1\",\"score\":0.9}]");

        List<NodeScore> result = classifier.classifyTargets("帮我调用已下线工具");

        assertTrue(result.isEmpty());
    }
}
