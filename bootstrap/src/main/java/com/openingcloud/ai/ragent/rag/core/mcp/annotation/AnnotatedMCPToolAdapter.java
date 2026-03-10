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

package com.openingcloud.ai.ragent.rag.core.mcp.annotation;

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolExecutor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 注解式 MCP 工具适配器
 * <p>
 * 将标注了 {@link MCPToolDeclare} 的 Bean 适配为 {@link MCPToolExecutor} 接口，
 * 从注解元数据自动构建 {@link MCPTool}，通过反射调用 {@link MCPExecute} 方法。
 */
public class AnnotatedMCPToolAdapter implements MCPToolExecutor {

    private final Object targetBean;
    private final Method executeMethod;
    private final MCPTool toolDefinition;

    public AnnotatedMCPToolAdapter(Object targetBean, MCPToolDeclare annotation, Method executeMethod) {
        this.targetBean = targetBean;
        this.executeMethod = executeMethod;
        this.executeMethod.setAccessible(true);
        this.toolDefinition = buildToolDefinition(annotation);
    }

    @Override
    public MCPTool getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public MCPResponse execute(MCPRequest request) {
        try {
            return (MCPResponse) executeMethod.invoke(targetBean, request);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return MCPResponse.error(
                    toolDefinition.getToolId(),
                    "EXECUTION_ERROR",
                    "工具执行异常: " + (cause != null ? cause.getMessage() : e.getMessage()),
                    MCPResponse.ErrorType.EXECUTION,
                    false
            );
        } catch (IllegalAccessException e) {
            return MCPResponse.error(
                    toolDefinition.getToolId(),
                    "ACCESS_ERROR",
                    "工具方法不可访问: " + e.getMessage(),
                    MCPResponse.ErrorType.EXECUTION,
                    false
            );
        }
    }

    private MCPTool buildToolDefinition(MCPToolDeclare annotation) {
        Map<String, MCPTool.ParameterDef> parameters = new LinkedHashMap<>();
        for (MCPParam param : annotation.parameters()) {
            MCPTool.ParameterDef paramDef = MCPTool.ParameterDef.builder()
                    .description(param.description())
                    .type(param.type())
                    .required(param.required())
                    .defaultValue(param.defaultValue().isEmpty() ? null : param.defaultValue())
                    .example(param.example().isEmpty() ? null : param.example())
                    .pattern(param.pattern().isEmpty() ? null : param.pattern())
                    .enumValues(param.enumValues().length > 0 ? Arrays.asList(param.enumValues()) : null)
                    .build();

            parameters.put(param.name(), paramDef);
        }

        List<String> examples = annotation.examples().length > 0
                ? Arrays.asList(annotation.examples())
                : List.of();

        return MCPTool.builder()
                .toolId(annotation.toolId())
                .name(annotation.name())
                .description(annotation.description())
                .useWhen(annotation.useWhen().isBlank() ? null : annotation.useWhen())
                .avoidWhen(annotation.avoidWhen().isBlank() ? null : annotation.avoidWhen())
                .examples(examples)
                .sceneKeywords(annotation.sceneKeywords().length > 0 ? Arrays.asList(annotation.sceneKeywords()) : List.of())
                .operationType(annotation.operationType())
                .requireUserId(annotation.requireUserId())
                .confirmationRequired(annotation.confirmationRequired())
                .timeoutSeconds(annotation.timeoutSeconds())
                .maxRetries(annotation.maxRetries())
                .sensitivity(annotation.sensitivity())
                .sensitiveParams(annotation.sensitiveParams().length > 0 ? Arrays.asList(annotation.sensitiveParams()) : List.of())
                .fallbackMessage(annotation.fallbackMessage().isBlank() ? null : annotation.fallbackMessage())
                .cacheableFallback(annotation.cacheableFallback())
                .fallbackCacheTtlSeconds(annotation.fallbackCacheTtlSeconds())
                .visibleToModel(annotation.visibleToModel())
                .parameters(parameters)
                .build();
    }
}
