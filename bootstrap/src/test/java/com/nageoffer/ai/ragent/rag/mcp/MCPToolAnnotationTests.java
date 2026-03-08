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

import com.nageoffer.ai.ragent.rag.core.mcp.*;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 工具注解系统单元测试
 */
class MCPToolAnnotationTests {

    private DefaultMCPToolRegistry registry;

    @BeforeEach
    void setUp() {
        // 创建空 Registry（不自动注入 Bean）
        registry = new DefaultMCPToolRegistry(List.of());
        registry.init();
    }

    // ─── 注解构建 MCPTool ───

    @Test
    void testAnnotationBuildsMCPTool() throws Exception {
        SampleAnnotatedTool tool = new SampleAnnotatedTool();
        MCPToolDeclare annotation = tool.getClass().getAnnotation(MCPToolDeclare.class);
        Method executeMethod = tool.getClass().getDeclaredMethod("execute", MCPRequest.class);

        AnnotatedMCPToolAdapter adapter = new AnnotatedMCPToolAdapter(tool, annotation, executeMethod);
        MCPTool toolDef = adapter.getToolDefinition();

        assertEquals("sample_tool", toolDef.getToolId());
        assertEquals("示例工具", toolDef.getName());
        assertEquals("用于测试的示例工具", toolDef.getDescription());
        assertEquals("当用户需要测试示例工具时使用", toolDef.getUseWhen());
        assertEquals("不要在生产写操作中使用", toolDef.getAvoidWhen());
        assertTrue(toolDef.isRequireUserId());
        assertEquals(List.of("测试", "查询"), toolDef.getSceneKeywords());
        assertTrue(toolDef.isConfirmationRequired());
        assertEquals(12, toolDef.getTimeoutSeconds());
        assertEquals(1, toolDef.getMaxRetries());
        assertEquals(MCPTool.Sensitivity.HIGH, toolDef.getSensitivity());
        assertEquals("工具降级提示", toolDef.getFallbackMessage());
        assertFalse(toolDef.isVisibleToModel());
        assertEquals(List.of("测试问题1", "测试问题2"), toolDef.getExamples());

        // 验证参数定义
        Map<String, MCPTool.ParameterDef> params = toolDef.getParameters();
        assertEquals(2, params.size());
        assertTrue(params.containsKey("keyword"));
        assertTrue(params.containsKey("limit"));

        MCPTool.ParameterDef keywordParam = params.get("keyword");
        assertEquals("搜索关键词", keywordParam.getDescription());
        assertEquals("string", keywordParam.getType());
        assertTrue(keywordParam.isRequired());
        assertEquals("Spring", keywordParam.getExample());
        assertEquals("^[A-Za-z]+$", keywordParam.getPattern());

        MCPTool.ParameterDef limitParam = params.get("limit");
        assertEquals("number", limitParam.getType());
        assertFalse(limitParam.isRequired());
        assertEquals("10", limitParam.getDefaultValue());
    }

    // ─── 适配器执行 ───

    @Test
    void testAdapterExecutesDelegateMethod() throws Exception {
        SampleAnnotatedTool tool = new SampleAnnotatedTool();
        MCPToolDeclare annotation = tool.getClass().getAnnotation(MCPToolDeclare.class);
        Method executeMethod = tool.getClass().getDeclaredMethod("execute", MCPRequest.class);

        AnnotatedMCPToolAdapter adapter = new AnnotatedMCPToolAdapter(tool, annotation, executeMethod);

        MCPRequest request = MCPRequest.builder()
                .toolId("sample_tool")
                .userId("user1")
                .userQuestion("测试")
                .build();
        request.addParameter("keyword", "Spring");

        MCPResponse response = adapter.execute(request);
        assertTrue(response.isSuccess());
        assertEquals("sample_tool", response.getToolId());
        assertTrue(response.getTextResult().contains("Spring"));
    }

    @Test
    void testAdapterHandlesExecutionException() throws Exception {
        ErrorThrowingTool tool = new ErrorThrowingTool();
        MCPToolDeclare annotation = tool.getClass().getAnnotation(MCPToolDeclare.class);
        Method executeMethod = tool.getClass().getDeclaredMethod("execute", MCPRequest.class);

        AnnotatedMCPToolAdapter adapter = new AnnotatedMCPToolAdapter(tool, annotation, executeMethod);

        MCPRequest request = MCPRequest.builder().toolId("error_tool").build();
        MCPResponse response = adapter.execute(request);

        assertFalse(response.isSuccess());
        assertEquals("EXECUTION_ERROR", response.getErrorCode());
    }

    // ─── 注册表注册 ───

    @Test
    void testAnnotationProcessorRegisters() throws Exception {
        MCPToolAnnotationProcessor processor = new MCPToolAnnotationProcessor(registry);

        SampleAnnotatedTool tool = new SampleAnnotatedTool();
        processor.postProcessAfterInitialization(tool, "sampleAnnotatedTool");

        assertTrue(registry.contains("sample_tool"));
        assertEquals(1, registry.size());

        MCPToolExecutor executor = registry.getExecutor("sample_tool").orElseThrow();
        assertEquals("示例工具", executor.getToolDefinition().getName());
    }

    @Test
    void testNonAnnotatedBeanIgnored() {
        MCPToolAnnotationProcessor processor = new MCPToolAnnotationProcessor(registry);

        // 普通对象不应被注册
        processor.postProcessAfterInitialization(new Object(), "plainBean");
        assertEquals(0, registry.size());
    }

    // ─── 与接口式共存 ───

    @Test
    void testCoexistsWithInterfaceStyle() throws Exception {
        // 先注册一个接口式工具
        MCPToolExecutor interfaceExecutor = new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder()
                        .toolId("interface_tool")
                        .name("接口式工具")
                        .description("测试")
                        .build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.success("interface_tool", "接口式结果");
            }
        };
        registry.register(interfaceExecutor);

        // 再注册一个注解式工具
        MCPToolAnnotationProcessor processor = new MCPToolAnnotationProcessor(registry);
        processor.postProcessAfterInitialization(new SampleAnnotatedTool(), "sampleAnnotatedTool");

        // 两个工具应共存
        assertEquals(2, registry.size());
        assertTrue(registry.contains("interface_tool"));
        assertTrue(registry.contains("sample_tool"));
    }

    // ─── 测试用内部类 ───

    @MCPToolDeclare(
            toolId = "sample_tool",
            name = "示例工具",
            description = "用于测试的示例工具",
            useWhen = "当用户需要测试示例工具时使用",
            avoidWhen = "不要在生产写操作中使用",
            examples = {"测试问题1", "测试问题2"},
            sceneKeywords = {"测试", "查询"},
            requireUserId = true,
            confirmationRequired = true,
            timeoutSeconds = 12,
            maxRetries = 1,
            sensitivity = MCPTool.Sensitivity.HIGH,
            fallbackMessage = "工具降级提示",
            visibleToModel = false,
            parameters = {
                    @MCPParam(name = "keyword", description = "搜索关键词", type = "string",
                            required = true, example = "Spring", pattern = "^[A-Za-z]+$"),
                    @MCPParam(name = "limit", description = "结果数量", type = "number", required = false, defaultValue = "10")
            }
    )
    static class SampleAnnotatedTool {

        @MCPExecute
        public MCPResponse execute(MCPRequest request) {
            String keyword = request.getStringParameter("keyword");
            return MCPResponse.success("sample_tool", "搜索结果: " + keyword);
        }
    }

    @MCPToolDeclare(
            toolId = "error_tool",
            name = "异常工具",
            description = "总是抛异常的测试工具"
    )
    static class ErrorThrowingTool {

        @MCPExecute
        public MCPResponse execute(MCPRequest request) {
            throw new RuntimeException("模拟执行异常");
        }
    }
}
