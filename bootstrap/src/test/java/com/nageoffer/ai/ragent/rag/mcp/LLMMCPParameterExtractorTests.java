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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
                .toolId("sales_query")
                .name("销售查询")
                .description("查询销售数据")
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
                .toolId("attendance_query")
                .name("考勤查询")
                .description("查询员工考勤")
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
}
