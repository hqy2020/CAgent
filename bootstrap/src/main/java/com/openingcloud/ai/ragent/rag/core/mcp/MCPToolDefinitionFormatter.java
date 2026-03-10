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

package com.openingcloud.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import java.util.Map;

/**
 * 统一格式化 MCP 工具定义，确保模型看到的工具描述稳定一致。
 */
public final class MCPToolDefinitionFormatter {

    private MCPToolDefinitionFormatter() {
    }

    public static String format(MCPTool tool) {
        if (tool == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("工具名称: ").append(StrUtil.blankToDefault(tool.getName(), tool.getToolId())).append("\n");
        sb.append("工具ID: ").append(StrUtil.blankToDefault(tool.getToolId(), "unknown")).append("\n");
        sb.append("功能描述: ").append(StrUtil.blankToDefault(tool.getDescription(), "无")).append("\n");
        if (StrUtil.isNotBlank(tool.getUseWhen())) {
            sb.append("何时使用: ").append(tool.getUseWhen()).append("\n");
        }
        if (StrUtil.isNotBlank(tool.getAvoidWhen())) {
            sb.append("避免使用: ").append(tool.getAvoidWhen()).append("\n");
        }
        if (CollUtil.isNotEmpty(tool.getSceneKeywords())) {
            sb.append("适用场景: ").append(String.join(" / ", tool.getSceneKeywords())).append("\n");
        }
        if (tool.isConfirmationRequired()) {
            sb.append("执行要求: 需要用户确认后执行\n");
        }
        if (CollUtil.isNotEmpty(tool.getExamples())) {
            sb.append("示例问题: ").append(String.join(" / ", tool.getExamples())).append("\n");
        }

        sb.append("参数列表:\n");
        if (tool.getParameters() == null || tool.getParameters().isEmpty()) {
            sb.append("  - 无\n");
            return sb.toString();
        }

        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String name = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();
            sb.append("  - ").append(name);
            sb.append(" (类型: ").append(StrUtil.blankToDefault(def.getType(), "string"));
            sb.append(def.isRequired() ? ", 必填" : ", 可选");
            if (def.getDefaultValue() != null) {
                sb.append(", 默认值: ").append(def.getDefaultValue());
            }
            if (StrUtil.isNotBlank(def.getExample())) {
                sb.append(", 示例: ").append(def.getExample());
            }
            if (StrUtil.isNotBlank(def.getPattern())) {
                sb.append(", 格式: ").append(def.getPattern());
            }
            if (CollUtil.isNotEmpty(def.getEnumValues())) {
                sb.append(", 可选值: ").append(String.join(", ", def.getEnumValues()));
            }
            sb.append("): ").append(StrUtil.blankToDefault(def.getDescription(), "无"));
            sb.append("\n");
        }

        return sb.toString();
    }
}
