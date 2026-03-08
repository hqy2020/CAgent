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

package com.nageoffer.ai.ragent.rag.mcp;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.LLMMCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_EXTRACT_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_REPAIR_PROMPT_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LLMMCPParameterExtractorTests {

    @Mock
    private LLMService llmService;

    @Mock
    private PromptTemplateLoader promptTemplateLoader;

    @InjectMocks
    private LLMMCPParameterExtractor extractor;

    @Test
    void shouldRetryAndRepairWhenRequiredEnumIsInvalid() {
        MCPTool tool = MCPTool.builder()
                .toolId("demo_query")
                .name("演示查询")
                .description("查询演示数据")
                .parameters(Map.of(
                        "region", MCPTool.ParameterDef.builder()
                                .type("string")
                                .required(true)
                                .enumValues(List.of("north", "south"))
                                .build(),
                        "top", MCPTool.ParameterDef.builder()
                                .type("integer")
                                .defaultValue(10)
                                .build()
                ))
                .build();

        when(promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH)).thenReturn("extract-prompt");
        when(promptTemplateLoader.load(MCP_PARAMETER_REPAIR_PROMPT_PATH)).thenReturn("repair-prompt");
        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("{\"region\":\"east\",\"top\":\"3\"}")
                .thenReturn("{\"region\":\"north\"}");

        Map<String, Object> params = extractor.extractParameters("查北区前三", tool, null);

        assertEquals("north", params.get("region"));
        assertEquals(3, params.get("top"));
        verify(llmService, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldKeepRequiredKeyAsNullWhenStillUnknownAfterRepair() {
        MCPTool tool = MCPTool.builder()
                .toolId("demo_required_query")
                .name("演示必填查询")
                .description("查询演示必填参数")
                .parameters(Map.of(
                        "employee", MCPTool.ParameterDef.builder()
                                .type("string")
                                .required(true)
                                .build(),
                        "period", MCPTool.ParameterDef.builder()
                                .type("string")
                                .defaultValue("current_week")
                                .build()
                ))
                .build();

        when(promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH)).thenReturn("extract-prompt");
        when(promptTemplateLoader.load(MCP_PARAMETER_REPAIR_PROMPT_PATH)).thenReturn("repair-prompt");
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{}", "{}");

        Map<String, Object> params = extractor.extractParameters("看下最近考勤", tool, null);

        assertTrue(params.containsKey("employee"));
        assertNull(params.get("employee"));
        assertEquals("current_week", params.get("period"));
        verify(llmService, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldPreferRuleBasedObsidianReadExtractionWithoutCallingLlm() {
        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_read")
                .name("读取笔记")
                .description("读取指定笔记")
                .parameters(Map.of(
                        "file", MCPTool.ParameterDef.builder()
                                .type("string")
                                .build(),
                        "path", MCPTool.ParameterDef.builder()
                                .type("string")
                                .build()
                ))
                .build();

        Map<String, Object> params = extractor.extractParameters("打开《Spring AOP 总结》这篇笔记", tool, null);

        assertEquals("Spring AOP 总结", params.get("file"));
        verifyNoInteractions(llmService);
    }

    @Test
    void shouldMapSnakeCaseKeysToCanonicalParameters() {
        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_search")
                .name("搜索笔记")
                .description("搜索")
                .parameters(Map.of(
                        "query", MCPTool.ParameterDef.builder()
                                .type("string")
                                .required(true)
                                .build(),
                        "withContext", MCPTool.ParameterDef.builder()
                                .type("string")
                                .enumValues(List.of("true", "false"))
                                .defaultValue("true")
                                .build()
                ))
                .build();

        when(promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH)).thenReturn("extract-prompt");
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"query\":\"HashMap\",\"with_context\":\"false\"}");

        Map<String, Object> params = extractor.extractParameters("请帮我处理这个 Obsidian 查询请求", tool, null);

        assertEquals("HashMap", params.get("query"));
        assertEquals("false", params.get("withContext"));
        verify(llmService, times(1)).chat(any(ChatRequest.class));
        verify(promptTemplateLoader, never()).load(MCP_PARAMETER_REPAIR_PROMPT_PATH);
    }

    @Test
    void shouldNotExtractDailyDateFromTodoTailForObsidianUpdate() {
        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_update")
                .name("更新笔记")
                .description("更新日记")
                .parameters(Map.of(
                        "daily", MCPTool.ParameterDef.builder()
                                .type("string")
                                .enumValues(List.of("true", "false"))
                                .defaultValue("false")
                                .build(),
                        "date", MCPTool.ParameterDef.builder()
                                .type("string")
                                .build()
                ))
                .build();

        Map<String, Object> params = extractor.extractParameters("帮我往今日日记加一条待办，3.7答辩", tool, null);

        assertEquals("true", params.get("daily"));
        assertFalse(params.containsKey("date"));
        verifyNoInteractions(llmService);
    }
}
