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

import com.nageoffer.ai.ragent.rag.core.mcp.DefaultMCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPServiceOrchestrator;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MCPRegistryAndServiceTests {

    private MCPToolExecutor createExecutor(String toolId, String name) {
        return new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder().toolId(toolId).name(name)
                        .description("Test tool " + name).build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.builder().success(true)
                        .textResult("Executed " + toolId).build();
            }
        };
    }

    @Test
    void testAutoDiscoveryRegistersAllExecutors() {
        MCPToolExecutor exec1 = createExecutor("tool-1", "Tool One");
        MCPToolExecutor exec2 = createExecutor("tool-2", "Tool Two");

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec1, exec2));
        registry.init();

        assertEquals(2, registry.size());
        assertTrue(registry.contains("tool-1"));
        assertTrue(registry.contains("tool-2"));
    }

    @Test
    void testGetExecutorByToolId() {
        MCPToolExecutor exec = createExecutor("query-tool", "Query Tool");
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();

        Optional<MCPToolExecutor> found = registry.getExecutor("query-tool");
        assertTrue(found.isPresent());
        assertEquals("query-tool", found.get().getToolId());

        Optional<MCPToolExecutor> notFound = registry.getExecutor("nonexistent");
        assertFalse(notFound.isPresent());
    }

    @Test
    void testUnregisterRemovesTool() {
        MCPToolExecutor exec = createExecutor("temp-tool", "Temp");
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();

        assertTrue(registry.contains("temp-tool"));
        registry.unregister("temp-tool");
        assertFalse(registry.contains("temp-tool"));
        assertEquals(0, registry.size());
    }

    @Test
    void testListAllTools() {
        MCPToolExecutor exec1 = createExecutor("t1", "Tool1");
        MCPToolExecutor exec2 = createExecutor("t2", "Tool2");
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec1, exec2));
        registry.init();

        List<MCPTool> tools = registry.listAllTools();
        assertEquals(2, tools.size());

        List<MCPToolExecutor> executors = registry.listAllExecutors();
        assertEquals(2, executors.size());
    }

    @Test
    void testRegisterOverwritesExisting() {
        MCPToolExecutor exec1 = createExecutor("dup", "Version1");
        MCPToolExecutor exec2 = createExecutor("dup", "Version2");

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec1));
        registry.init();
        registry.register(exec2);

        assertEquals(1, registry.size());
        assertEquals("Version2", registry.getExecutor("dup").get().getToolDefinition().getName());
    }

    @Test
    void testToolExecutorSupportsMethod() {
        MCPToolExecutor exec = createExecutor("my-tool", "My Tool");
        MCPRequest matching = MCPRequest.builder().toolId("my-tool").build();
        MCPRequest nonMatching = MCPRequest.builder().toolId("other-tool").build();

        assertTrue(exec.supports(matching));
        assertFalse(exec.supports(nonMatching));
    }

    @Test
    void testServiceShouldRequireConfirmationForSensitiveTool() {
        MCPToolExecutor exec = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("obsidian_create")
                        .name("Create Note")
                        .description("create")
                        .requireUserId(false)
                        .confirmationRequired(true)
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("obsidian_create", "created");
            }
        };

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();
        MCPServiceOrchestrator service = new MCPServiceOrchestrator(registry, Runnable::run);

        MCPResponse blocked = service.execute(MCPRequest.builder().toolId("obsidian_create").build());
        assertFalse(blocked.isSuccess());
        assertEquals("CONFIRMATION_REQUIRED", blocked.getErrorCode());

        MCPResponse confirmed = service.execute(MCPRequest.builder()
                .toolId("obsidian_create")
                .confirmed(true)
                .build());
        assertTrue(confirmed.isSuccess());
    }

    @Test
    void testBuildToolsDescriptionShouldHideLegacyAliasTools() {
        MCPToolExecutor visible = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("visible_tool")
                        .name("Visible Tool")
                        .description("visible")
                        .requireUserId(false)
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("visible_tool", "ok");
            }
        };

        MCPToolExecutor hidden = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("legacy_hidden_tool")
                        .name("Hidden Tool")
                        .description("hidden")
                        .requireUserId(false)
                        .visibleToModel(false)
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("legacy_hidden_tool", "ok");
            }
        };

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(visible, hidden));
        registry.init();
        MCPServiceOrchestrator service = new MCPServiceOrchestrator(registry, Runnable::run);

        String description = service.buildToolsDescription();
        assertTrue(description.contains("visible_tool"));
        assertFalse(description.contains("legacy_hidden_tool"));
    }

    @Test
    void testBuildToolsDescriptionShouldIncludeUseWhenAndParameterHints() {
        MCPToolExecutor exec = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("obsidian_update")
                        .name("更新笔记")
                        .description("向已有笔记追加内容")
                        .useWhen("当用户明确要补充已有笔记时使用。")
                        .avoidWhen("不要用于新建笔记。")
                        .requireUserId(false)
                        .parameters(Map.of(
                                "date", MCPTool.ParameterDef.builder()
                                        .type("string")
                                        .description("目标日期")
                                        .example("2026-03-08")
                                        .pattern("^\\d{4}-\\d{2}-\\d{2}$")
                                        .build()
                        ))
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("obsidian_update", "ok");
            }
        };

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();
        MCPServiceOrchestrator service = new MCPServiceOrchestrator(registry, Runnable::run);

        String description = service.buildToolsDescription();
        assertTrue(description.contains("何时使用"));
        assertTrue(description.contains("避免使用"));
        assertTrue(description.contains("示例: 2026-03-08"));
        assertTrue(description.contains("格式: ^\\d{4}-\\d{2}-\\d{2}$"));
    }

    @Test
    void testServiceShouldReturnStructuredValidationErrorForMissingParam() {
        MCPToolExecutor exec = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("obsidian_create")
                        .name("Create Note")
                        .description("create")
                        .requireUserId(false)
                        .parameters(Map.of(
                                "name", MCPTool.ParameterDef.builder()
                                        .type("string")
                                        .description("笔记名称")
                                        .required(true)
                                        .example("AI 工具调用设计")
                                        .build()
                        ))
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("obsidian_create", "created");
            }
        };

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();
        MCPServiceOrchestrator service = new MCPServiceOrchestrator(registry, Runnable::run);

        MCPResponse response = service.execute(MCPRequest.builder().toolId("obsidian_create").build());

        assertFalse(response.isSuccess());
        assertEquals("MISSING_PARAM", response.getErrorCode());
        assertEquals("INVALID_PARAMETER", response.getStandardErrorCode());
        assertEquals("name", response.getErrorDetails().get("parameter"));
        assertTrue(response.getUserActionHint().contains("AI 工具调用设计"));
    }

    @Test
    void testServiceShouldNormalizeIntegerParametersBeforeExecuting() {
        MCPToolExecutor exec = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("web_news_search")
                        .name("Web Search")
                        .description("search")
                        .requireUserId(false)
                        .parameters(Map.of(
                                "limit", MCPTool.ParameterDef.builder()
                                        .type("integer")
                                        .description("条数")
                                        .required(false)
                                        .build()
                        ))
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                Object limit = request.getParameter("limit");
                return MCPResponse.success("web_news_search", limit instanceof Integer ? "ok" : "bad");
            }
        };

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();
        MCPServiceOrchestrator service = new MCPServiceOrchestrator(registry, Runnable::run);

        MCPRequest request = MCPRequest.builder().toolId("web_news_search").build();
        request.addParameter("limit", "5");
        MCPResponse response = service.execute(request);

        assertTrue(response.isSuccess());
        assertEquals("ok", response.getTextResult());
        assertTrue(request.getParameter("limit") instanceof Integer);
    }

    @Test
    void testServiceShouldRejectPatternMismatchWithStructuredDetails() {
        MCPToolExecutor exec = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("obsidian_update")
                        .name("Update Note")
                        .description("update")
                        .requireUserId(false)
                        .parameters(Map.of(
                                "date", MCPTool.ParameterDef.builder()
                                        .type("string")
                                        .description("目标日期")
                                        .required(true)
                                        .example("2026-03-08")
                                        .pattern("^\\d{4}-\\d{2}-\\d{2}$")
                                        .build()
                        ))
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("obsidian_update", "ok");
            }
        };

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();
        MCPServiceOrchestrator service = new MCPServiceOrchestrator(registry, Runnable::run);

        MCPRequest request = MCPRequest.builder().toolId("obsidian_update").build();
        request.addParameter("date", "03/08/2026");
        MCPResponse response = service.execute(request);

        assertFalse(response.isSuccess());
        assertEquals("PARAM_PATTERN_MISMATCH", response.getErrorCode());
        assertEquals("INVALID_PARAMETER", response.getStandardErrorCode());
        assertEquals("^\\d{4}-\\d{2}-\\d{2}$", response.getErrorDetails().get("pattern"));
    }
}
