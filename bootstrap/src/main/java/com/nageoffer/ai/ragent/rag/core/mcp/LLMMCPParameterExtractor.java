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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_EXTRACT_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_REPAIR_PROMPT_PATH;

/**
 * 基于 LLM 的 MCP 参数提取器实现（V3 Enterprise 专用）
 * <p>
 * 使用大模型从用户问题中智能提取工具调用所需的参数。
 * 适合处理复杂的自然语言参数提取场景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMMCPParameterExtractor implements MCPParameterExtractor {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final Gson gson = new Gson();

    @Override
    public Map<String, Object> extractParameters(String userQuestion, MCPTool tool) {
        return extractParameters(userQuestion, tool, null);
    }

    @Override
    public Map<String, Object> extractParameters(String userQuestion, MCPTool tool, String customPromptTemplate) {
        if (tool == null || CollUtil.isEmpty(tool.getParameters())) {
            return Collections.emptyMap();
        }

        try {
            String basePrompt = StrUtil.isNotBlank(customPromptTemplate)
                    ? customPromptTemplate
                    : promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH);

            Map<String, Object> extracted = runExtraction(
                    userQuestion,
                    tool,
                    basePrompt,
                    Map.of(),
                    List.of(),
                    false
            );
            Map<String, Object> normalized = normalizeBySchema(extracted, tool);
            fillDefaults(normalized, tool);

            MCPParameterExtractor.ParameterValidationResult validation = validate(normalized, tool);
            if (!validation.valid()) {
                normalized = repairAndMerge(
                        userQuestion,
                        tool,
                        customPromptTemplate,
                        normalized,
                        validation.missingParams()
                );
            }

            fillDefaults(normalized, tool);
            ensureRequiredKeys(normalized, tool);

            log.info("MCP 参数提取完成, toolId: {}, 使用自定义提示词: {}, 参数: {}",
                    tool.getToolId(), StrUtil.isNotBlank(customPromptTemplate), normalized);
            return normalized;
        } catch (Exception e) {
            log.error("MCP 参数提取异常, toolId: {}", tool.getToolId(), e);
            return buildDefaultParameters(tool);
        }
    }

    private Map<String, Object> buildDefaultParameters(MCPTool tool) {
        Map<String, Object> defaultParams = new LinkedHashMap<>();
        fillDefaults(defaultParams, tool);
        ensureRequiredKeys(defaultParams, tool);
        return defaultParams;
    }

    private Map<String, Object> runExtraction(String userQuestion,
                                              MCPTool tool,
                                              String systemPrompt,
                                              Map<String, Object> currentParams,
                                              List<String> missingParams,
                                              boolean retryMode) {
        List<ChatMessage> messages = buildMessages(
                systemPrompt,
                userQuestion,
                tool,
                currentParams,
                missingParams,
                retryMode
        );
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
        String raw = llmService.chat(request);
        log.info("MCP 参数提取 LLM 响应, toolId: {}, retryMode: {}, raw: {}",
                tool.getToolId(), retryMode, raw);
        return parseJsonResponse(raw, tool);
    }

    private List<ChatMessage> buildMessages(String systemPrompt,
                                            String userQuestion,
                                            MCPTool tool,
                                            Map<String, Object> currentParams,
                                            List<String> missingParams,
                                            boolean retryMode) {
        List<ChatMessage> messages = new ArrayList<>(5);
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user("""
                <tool_definition>
                %s
                </tool_definition>
                """.formatted(buildToolDefinition(tool))));
        messages.add(ChatMessage.user("""
                <user_question>
                %s
                </user_question>
                """.formatted(StrUtil.emptyIfNull(userQuestion))));

        if (retryMode) {
            messages.add(ChatMessage.user("""
                    <current_params>
                    %s
                    </current_params>

                    <missing_required>
                    %s
                    </missing_required>
                    """.formatted(gson.toJson(currentParams), String.join(", ", missingParams))));
        }
        messages.add(ChatMessage.user("仅输出 JSON 对象，不要附加解释。"));
        return messages;
    }

    private Map<String, Object> repairAndMerge(String userQuestion,
                                               MCPTool tool,
                                               String customPromptTemplate,
                                               Map<String, Object> currentParams,
                                               List<String> missingParams) {
        String repairPrompt = promptTemplateLoader.load(MCP_PARAMETER_REPAIR_PROMPT_PATH);
        if (StrUtil.isNotBlank(customPromptTemplate)) {
            repairPrompt = PromptTemplateUtils.cleanupPrompt(customPromptTemplate + "\n\n" + repairPrompt);
        }

        Map<String, Object> repaired = runExtraction(
                userQuestion,
                tool,
                repairPrompt,
                currentParams,
                missingParams,
                true
        );

        Map<String, Object> merged = new LinkedHashMap<>(currentParams);
        merged.putAll(normalizeBySchema(repaired, tool));
        fillDefaults(merged, tool);
        ensureRequiredKeys(merged, tool);
        return merged;
    }

    /**
     * 构建工具定义描述（供 LLM 理解）
     */
    private String buildToolDefinition(MCPTool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具名称: ").append(tool.getName()).append("\n");
        sb.append("工具ID: ").append(tool.getToolId()).append("\n");
        sb.append("功能描述: ").append(tool.getDescription()).append("\n");
        sb.append("参数列表:\n");

        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();

            sb.append("  - ").append(paramName);
            sb.append(" (类型: ").append(def.getType());
            sb.append(def.isRequired() ? ", 必填" : ", 可选");
            sb.append("): ").append(def.getDescription());

            if (def.getDefaultValue() != null) {
                sb.append(" [默认值: ").append(def.getDefaultValue()).append("]");
            }
            if (CollUtil.isNotEmpty(def.getEnumValues())) {
                sb.append(" [可选值: ").append(String.join(", ", def.getEnumValues())).append("]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     */
    private Map<String, Object> parseJsonResponse(String raw, MCPTool tool) {
        if (StrUtil.isBlank(raw)) {
            return new LinkedHashMap<>();
        }
        // 清理可能的 markdown 代码块
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonElement element;
        try {
            element = JsonParser.parseString(cleaned);
        } catch (JsonSyntaxException e) {
            log.warn("MCP 参数提取-JSON解析失败, toolId: {}, cleaned: {}", tool.getToolId(), cleaned, e);
            return new LinkedHashMap<>();
        }
        if (!element.isJsonObject()) {
            log.warn("LLM 返回的不是 JSON 对象: {}", raw);
            return new LinkedHashMap<>();
        }
        JsonObject obj = element.getAsJsonObject();
        Map<String, Object> result = new LinkedHashMap<>();
        // 只提取工具定义中声明的参数
        for (String paramName : tool.getParameters().keySet()) {
            if (obj.has(paramName) && !obj.get(paramName).isJsonNull()) {
                JsonElement value = obj.get(paramName);
                result.put(paramName, convertJsonElement(value));
            }
        }
        return result;
    }

    /**
     * 转换 JsonElement 为普通 Java 对象
     */
    private Object convertJsonElement(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double d = primitive.getAsDouble();

                if (Double.isNaN(d)) {
                    return null;
                }

                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        return (int) d;
                    } else if (d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                        return (long) d;
                    }
                }
                return d;
            } else if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else {
                return primitive.getAsString();
            }
        } else if (element.isJsonArray()) {
            return gson.fromJson(element, List.class);
        } else if (element.isJsonObject()) {
            return gson.fromJson(element, LinkedHashMap.class);
        }
        return null;
    }

    private Map<String, Object> normalizeBySchema(Map<String, Object> extracted, MCPTool tool) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (tool == null || tool.getParameters() == null || extracted == null || extracted.isEmpty()) {
            return normalized;
        }

        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();
            if (!extracted.containsKey(paramName)) {
                continue;
            }

            Object rawValue = extracted.get(paramName);
            Object converted = convertToExpectedType(rawValue, def.getType());
            if (converted == null) {
                continue;
            }

            Object enumAligned = alignEnumValue(converted, def);
            if (enumAligned == null) {
                continue;
            }
            normalized.put(paramName, enumAligned);
        }

        return normalized;
    }

    private Object alignEnumValue(Object value, MCPTool.ParameterDef def) {
        if (def == null || CollUtil.isEmpty(def.getEnumValues())) {
            return value;
        }
        String actual = StrUtil.trim(value.toString());
        for (String candidate : def.getEnumValues()) {
            if (StrUtil.equalsIgnoreCase(StrUtil.trim(candidate), actual)) {
                return candidate;
            }
        }
        return null;
    }

    private Object convertToExpectedType(Object value, String type) {
        if (value == null) {
            return null;
        }
        String normalizedType = StrUtil.blankToDefault(type, "string")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "string" -> convertToString(value);
            case "integer" -> convertToInteger(value);
            case "number" -> convertToNumber(value);
            case "boolean" -> convertToBoolean(value);
            case "array" -> convertToArray(value);
            case "object" -> value instanceof Map<?, ?> ? value : null;
            default -> value;
        };
    }

    private String convertToString(Object value) {
        String str = StrUtil.trim(value.toString());
        return StrUtil.isEmpty(str) ? null : str;
    }

    private Integer convertToInteger(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            if (d % 1 != 0) {
                return null;
            }
            return (int) d;
        }
        if (value instanceof String str) {
            String trimmed = StrUtil.trim(str);
            if (StrUtil.isBlank(trimmed)) {
                return null;
            }
            if (!trimmed.matches("[-+]?\\d+")) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Double convertToNumber(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return d;
        }
        if (value instanceof String str) {
            String trimmed = StrUtil.trim(str);
            if (StrUtil.isBlank(trimmed)) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0D;
        }
        if (value instanceof String str) {
            String normalized = StrUtil.trim(str).toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "true", "yes", "y", "1", "是", "要", "开启", "需要" -> true;
                case "false", "no", "n", "0", "否", "不要", "关闭", "不需要" -> false;
                default -> null;
            };
        }
        return null;
    }

    private List<?> convertToArray(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof String str) {
            String trimmed = StrUtil.trim(str);
            if (StrUtil.isBlank(trimmed)) {
                return null;
            }
            String[] parts = trimmed.split("[,，]");
            List<String> values = new ArrayList<>();
            for (String part : parts) {
                String item = StrUtil.trim(part);
                if (StrUtil.isNotBlank(item)) {
                    values.add(item);
                }
            }
            return values.isEmpty() ? null : values;
        }
        return null;
    }


    /**
     * 填充默认值
     */
    private void fillDefaults(Map<String, Object> params, MCPTool tool) {
        if (tool.getParameters() == null) {
            return;
        }

        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();

            if (!params.containsKey(paramName) && def.getDefaultValue() != null) {
                params.put(paramName, def.getDefaultValue());
            }
        }
    }

    private void ensureRequiredKeys(Map<String, Object> params, MCPTool tool) {
        if (tool == null || tool.getParameters() == null) {
            return;
        }
        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();
            if (!def.isRequired()) {
                continue;
            }
            if (!params.containsKey(paramName)) {
                if (def.getDefaultValue() != null) {
                    params.put(paramName, def.getDefaultValue());
                } else {
                    params.put(paramName, null);
                }
            }
        }
    }
}
