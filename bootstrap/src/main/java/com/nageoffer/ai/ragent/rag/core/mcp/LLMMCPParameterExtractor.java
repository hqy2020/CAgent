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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Map<String, Map<String, List<String>>> PARAMETER_ALIASES = Map.of(
            "obsidian_search", Map.of(
                    "withContext", List.of("with_context"),
                    "path", List.of("folder", "dir", "directory")
            ),
            "obsidian_list", Map.of(
                    "folder", List.of("path", "dir", "directory")
            ),
            "obsidian_read", Map.of(
                    "file", List.of("filename", "title", "note"),
                    "path", List.of("filepath")
            )
    );

    private static final Map<String, Map<String, Map<String, String>>> ENUM_ALIASES = Map.of(
            "obsidian_search", Map.of(
                    "withContext", Map.of("yes", "true", "true", "true", "需要", "true", "no", "false", "false", "false")
            )
    );

    private static final Pattern TOP_N_PATTERN = Pattern.compile("(?:前|top\\s*)(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d{1,2})\\s*(?:条|个|篇|则)");
    private static final Pattern QUOTED_TITLE_PATTERN = Pattern.compile("《([^》]+)》");
    private static final Pattern PATH_PATTERN = Pattern.compile("([\\p{L}\\p{N}_./（）()\\-]+(?:\\.md)?)");
    private static final Pattern DATE_ISO_PATTERN = Pattern.compile("(\\d{4})[-./年](\\d{1,2})[-./月](\\d{1,2})日?");
    private static final Pattern DATE_MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})(?!\\d)");
    private static final Pattern DATE_CN_MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern TODAY_DAILY_PATTERN = Pattern.compile("今日日记|今天(?:的)?日记|今日(?:的)?日记");
    private static final Pattern TARGET_DAILY_ISO_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{4})[-./年](\\d{1,2})[-./月](\\d{1,2})日?\\s*(?:的)?\\s*日记");
    private static final Pattern TARGET_DAILY_MONTH_DAY_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})\\s*(?:的)?\\s*日记");
    private static final Pattern TARGET_DAILY_CN_MONTH_DAY_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日\\s*(?:的)?\\s*日记");

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
            Map<String, Object> ruleBased = normalizeBySchema(extractRuleBasedParameters(userQuestion, tool), tool);
            Map<String, Object> ruleResolved = new LinkedHashMap<>(ruleBased);
            fillDefaults(ruleResolved, tool);
            ensureRequiredKeys(ruleResolved, tool);
            MCPParameterExtractor.ParameterValidationResult ruleValidation = validate(new LinkedHashMap<>(ruleResolved), tool);
            if (ruleValidation.valid() && !ruleBased.isEmpty()) {
                log.info("MCP 参数提取命中规则层, toolId: {}, 参数: {}", tool.getToolId(), ruleResolved);
                return ruleResolved;
            }

            String basePrompt = StrUtil.isNotBlank(customPromptTemplate)
                    ? customPromptTemplate
                    : promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH);

            Map<String, Object> extracted = runExtraction(userQuestion, tool, basePrompt, ruleBased, List.of(), false);
            Map<String, Object> normalized = mergeParameters(ruleBased, normalizeBySchema(extracted, tool));
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
                """.formatted(MCPToolDefinitionFormatter.format(tool))));
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
        Map<String, String> aliasIndex = buildParameterAliasIndex(tool);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            String canonicalName = aliasIndex.get(normalizeParamKey(entry.getKey()));
            if (canonicalName == null || result.containsKey(canonicalName)) {
                continue;
            }
            result.put(canonicalName, convertJsonElement(entry.getValue()));
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

            Object enumAligned = alignEnumValue(tool, paramName, converted, def);
            if (enumAligned == null) {
                continue;
            }
            normalized.put(paramName, enumAligned);
        }

        return normalized;
    }

    private Object alignEnumValue(MCPTool tool, String paramName, Object value, MCPTool.ParameterDef def) {
        if (def == null || CollUtil.isEmpty(def.getEnumValues())) {
            return value;
        }
        String actual = StrUtil.trim(value.toString());
        for (String candidate : def.getEnumValues()) {
            if (StrUtil.equalsIgnoreCase(StrUtil.trim(candidate), actual)) {
                return candidate;
            }
        }
        Map<String, String> aliases = ENUM_ALIASES.getOrDefault(tool.getToolId(), Map.of()).get(paramName);
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        String canonical = aliases.get(normalizeParamKey(actual));
        if (canonical == null) {
            canonical = aliases.get(actual.toLowerCase(Locale.ROOT));
        }
        if (canonical == null) {
            return null;
        }
        for (String candidate : def.getEnumValues()) {
            if (StrUtil.equalsIgnoreCase(StrUtil.trim(candidate), canonical)) {
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

    private Map<String, Object> mergeParameters(Map<String, Object> preferred, Map<String, Object> secondary) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (preferred != null) {
            merged.putAll(preferred);
        }
        if (secondary != null) {
            secondary.forEach(merged::putIfAbsent);
        }
        return merged;
    }

    private Map<String, String> buildParameterAliasIndex(MCPTool tool) {
        Map<String, String> aliasIndex = new LinkedHashMap<>();
        if (tool == null || tool.getParameters() == null) {
            return aliasIndex;
        }
        Map<String, List<String>> toolAliases = PARAMETER_ALIASES.getOrDefault(tool.getToolId(), Map.of());
        for (String paramName : tool.getParameters().keySet()) {
            registerAlias(aliasIndex, paramName, paramName);
            registerAlias(aliasIndex, toSnakeCase(paramName), paramName);
            registerAlias(aliasIndex, toKebabCase(paramName), paramName);
            registerAlias(aliasIndex, normalizeParamKey(paramName), paramName);
            for (String alias : toolAliases.getOrDefault(paramName, List.of())) {
                registerAlias(aliasIndex, alias, paramName);
            }
        }
        return aliasIndex;
    }

    private void registerAlias(Map<String, String> aliasIndex, String alias, String paramName) {
        if (StrUtil.isBlank(alias) || StrUtil.isBlank(paramName)) {
            return;
        }
        aliasIndex.putIfAbsent(normalizeParamKey(alias), paramName);
    }

    private String normalizeParamKey(String raw) {
        return StrUtil.blankToDefault(raw, "")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    private String toSnakeCase(String raw) {
        return raw.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private String toKebabCase(String raw) {
        return raw.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> extractRuleBasedParameters(String userQuestion, MCPTool tool) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (tool == null || tool.getParameters() == null || tool.getParameters().isEmpty()) {
            return params;
        }
        String question = StrUtil.blankToDefault(userQuestion, "").trim();
        if (question.isEmpty()) {
            return params;
        }

        if (tool.getParameters().containsKey("limit")) {
            Integer limit = extractLimit(question);
            if (limit != null) {
                params.put("limit", limit);
            }
        }
        if (tool.getParameters().containsKey("date") && shouldExtractRuleBasedDate(tool.getToolId(), question)) {
            String date = extractDate(question);
            if (date != null) {
                params.put("date", date);
            }
        }

        switch (tool.getToolId()) {
            case "obsidian_search" -> extractObsidianSearchParameters(question, params);
            case "obsidian_read" -> extractObsidianReadParameters(question, params);
            case "obsidian_list" -> extractObsidianListParameters(question, params);
            case "obsidian_update" -> extractObsidianUpdateParameters(question, params);
            case "web_news_search" -> extractWebSearchParameters(question, params);
            default -> {
                // no-op
            }
        }
        return params;
    }

    private boolean shouldExtractRuleBasedDate(String toolId, String question) {
        if (!"obsidian_update".equals(toolId)) {
            return true;
        }
        return extractExplicitDailyDate(question) != null;
    }

    private void extractObsidianSearchParameters(String question, Map<String, Object> params) {
        String title = extractQuotedTitle(question);
        if (title != null) {
            params.putIfAbsent("query", title);
        }
        String extracted = extractSearchTopic(question, "笔记");
        if (extracted != null) {
            params.putIfAbsent("query", extracted);
        }
        String path = extractPathCandidate(question);
        if (path != null && path.contains("/")) {
            params.putIfAbsent("path", path);
        }
    }

    private void extractObsidianReadParameters(String question, Map<String, Object> params) {
        String path = extractPathCandidate(question);
        if (path != null && (path.contains("/") || path.endsWith(".md"))) {
            params.putIfAbsent("path", path);
            return;
        }
        String title = extractQuotedTitle(question);
        if (title != null) {
            params.putIfAbsent("file", title);
        }
    }

    private void extractObsidianListParameters(String question, Map<String, Object> params) {
        if (question.contains("文件夹") || question.contains("目录")) {
            params.putIfAbsent("type", "folders");
        }
        String path = extractPathCandidate(question);
        if (path != null && path.contains("/")) {
            params.putIfAbsent("folder", path.replaceAll("\\.md$", ""));
        }
    }

    private void extractObsidianUpdateParameters(String question, Map<String, Object> params) {
        LocalDate explicitDailyDate = extractExplicitDailyDate(question);
        boolean todayDaily = containsTodayDailySemantic(question);
        if (todayDaily || explicitDailyDate != null) {
            params.putIfAbsent("daily", "true");
        }
        if (explicitDailyDate != null) {
            params.putIfAbsent("date", explicitDailyDate.toString());
            return;
        }
        if ("true".equals(String.valueOf(params.get("daily")))) {
            return;
        }
        String path = extractPathCandidate(question);
        if (path != null && (path.contains("/") || path.endsWith(".md"))) {
            if (path.contains("/")) {
                params.putIfAbsent("path", path);
            } else {
                params.putIfAbsent("file", path.replaceAll("\\.md$", ""));
            }
            return;
        }
        String title = extractQuotedTitle(question);
        if (title != null) {
            params.putIfAbsent("file", title);
        }
    }

    private void extractWebSearchParameters(String question, Map<String, Object> params) {
        String extracted = question
                .replace("帮我", "")
                .replace("联网", "")
                .replace("上网", "")
                .replace("搜索", "")
                .replace("查一下", "")
                .replace("查一查", "")
                .replace("今天", "")
                .trim();
        extracted = extracted.replaceAll("的\\s*\\d+\\s*条新闻.*$", "")
                .replaceAll("的\\s*热点.*$", "")
                .replaceAll("新闻.*$", "")
                .trim();
        if (StrUtil.isNotBlank(extracted)) {
            params.putIfAbsent("query", extracted);
        }
    }

    private Integer extractLimit(String question) {
        Matcher topMatcher = TOP_N_PATTERN.matcher(question);
        if (topMatcher.find()) {
            return safeParseInt(topMatcher.group(1));
        }
        Matcher countMatcher = COUNT_PATTERN.matcher(question);
        if (countMatcher.find()) {
            return safeParseInt(countMatcher.group(1));
        }
        return null;
    }

    private String extractDate(String question) {
        String trimmed = question.trim();
        if (trimmed.contains("今天")) {
            return java.time.LocalDate.now().toString();
        }
        if (trimmed.contains("昨天")) {
            return java.time.LocalDate.now().minusDays(1).toString();
        }
        if (trimmed.contains("明天")) {
            return java.time.LocalDate.now().plusDays(1).toString();
        }
        Matcher isoMatcher = DATE_ISO_PATTERN.matcher(trimmed);
        if (isoMatcher.find()) {
            return buildDate(safeParseInt(isoMatcher.group(1)), safeParseInt(isoMatcher.group(2)), safeParseInt(isoMatcher.group(3)));
        }
        Matcher monthDayMatcher = DATE_MONTH_DAY_PATTERN.matcher(trimmed);
        if (monthDayMatcher.find()) {
            return buildDate(java.time.LocalDate.now().getYear(), safeParseInt(monthDayMatcher.group(1)), safeParseInt(monthDayMatcher.group(2)));
        }
        Matcher cnMonthDayMatcher = DATE_CN_MONTH_DAY_PATTERN.matcher(trimmed);
        if (cnMonthDayMatcher.find()) {
            return buildDate(java.time.LocalDate.now().getYear(), safeParseInt(cnMonthDayMatcher.group(1)), safeParseInt(cnMonthDayMatcher.group(2)));
        }
        return null;
    }

    private boolean containsTodayDailySemantic(String question) {
        return StrUtil.isNotBlank(question) && TODAY_DAILY_PATTERN.matcher(question).find();
    }

    private LocalDate extractExplicitDailyDate(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        Matcher isoMatcher = TARGET_DAILY_ISO_PATTERN.matcher(question);
        if (isoMatcher.find()) {
            return buildLocalDate(safeParseInt(isoMatcher.group(1)), safeParseInt(isoMatcher.group(2)), safeParseInt(isoMatcher.group(3)));
        }
        Matcher monthDayMatcher = TARGET_DAILY_MONTH_DAY_PATTERN.matcher(question);
        if (monthDayMatcher.find()) {
            return buildLocalDate(LocalDate.now().getYear(), safeParseInt(monthDayMatcher.group(1)), safeParseInt(monthDayMatcher.group(2)));
        }
        Matcher cnMonthDayMatcher = TARGET_DAILY_CN_MONTH_DAY_PATTERN.matcher(question);
        if (cnMonthDayMatcher.find()) {
            return buildLocalDate(LocalDate.now().getYear(), safeParseInt(cnMonthDayMatcher.group(1)), safeParseInt(cnMonthDayMatcher.group(2)));
        }
        return null;
    }

    private LocalDate buildLocalDate(int year, int month, int day) {
        if (year <= 0 || month <= 0 || day <= 0) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractQuotedTitle(String question) {
        Matcher matcher = QUOTED_TITLE_PATTERN.matcher(question);
        return matcher.find() ? StrUtil.trim(matcher.group(1)) : null;
    }

    private String extractPathCandidate(String question) {
        Matcher matcher = PATH_PATTERN.matcher(question);
        while (matcher.find()) {
            String candidate = StrUtil.trim(matcher.group(1));
            if (candidate.contains("/") || candidate.endsWith(".md")) {
                return candidate;
            }
        }
        return null;
    }

    private String extractSearchTopic(String question, String tailWord) {
        Matcher intentMatcher = Pattern.compile("(搜索|查找|搜一下|搜搜|找一下)").matcher(question);
        if (!intentMatcher.find()) {
            return null;
        }
        String candidate = question.substring(intentMatcher.end());
        candidate = candidate.replace("Obsidian", "")
                .replace("obsidian", "")
                .replace("里", "")
                .replace("中", "")
                .replace("相关", "")
                .replace(tailWord, "")
                .replace("资料", "")
                .replace("学习", "")
                .trim();
        candidate = candidate.replaceAll("^[的是关于\\s]+", "").trim();
        candidate = candidate.replaceAll("[？?。！!]+$", "").trim();
        return StrUtil.isBlank(candidate) ? null : candidate;
    }

    private Integer safeParseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildDate(int year, int month, int day) {
        if (year <= 0 || month <= 0 || day <= 0) {
            return null;
        }
        try {
            return java.time.LocalDate.of(year, month, day).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
