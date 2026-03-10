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

package com.openingcloud.ai.ragent.rag.core.mcp.governance;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 请求/响应脱敏器。
 */
@Component
public class MCPPayloadSanitizer {

    private static final Set<String> DEFAULT_SENSITIVE_KEYS = Set.of(
            "content", "oldcontent", "newcontent", "token", "apikey", "authorization", "sourceurl", "url"
    );

    private final RagMcpExecutionProperties properties;

    public MCPPayloadSanitizer(RagMcpExecutionProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> sanitizeRequest(MCPRequest request, MCPTool tool) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (request == null) {
            return result;
        }
        result.put("toolId", request.getToolId());
        result.put("requestSource", request.getRequestSource());
        result.put("confirmed", request.isConfirmed());
        result.put("parameters", sanitizeMap(request.getParameters(), buildSensitiveKeys(tool)));
        if (StrUtil.isNotBlank(request.getUserQuestion())) {
            result.put("questionLength", request.getUserQuestion().length());
        }
        return result;
    }

    public Map<String, Object> sanitizeResponse(MCPResponse response, MCPTool tool) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (response == null) {
            return result;
        }
        result.put("success", response.isSuccess());
        result.put("toolId", response.getToolId());
        result.put("fallbackUsed", response.isFallbackUsed());
        result.put("standardErrorCode", response.getStandardErrorCode());
        result.put("errorCode", response.getErrorCode());
        if (StrUtil.isNotBlank(response.getErrorMessage())) {
            result.put("errorMessage", truncateText(response.getErrorMessage()));
        }
        if (response.getTextResult() != null) {
            result.put("textLength", response.getTextResult().length());
        }
        if (response.getData() != null && !response.getData().isEmpty()) {
            result.put("data", sanitizeMap(response.getData(), buildSensitiveKeys(tool)));
        }
        return result;
    }

    public Map<String, Object> sanitizeMap(Map<String, ?> raw, Set<String> sensitiveKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            result.put(key, sanitizeValue(key, value, sensitiveKeys));
        }
        return result;
    }

    public String truncateText(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= properties.getAuditPayloadMaxChars()) {
            return value;
        }
        return value.substring(0, properties.getAuditPayloadMaxChars()) + "...(truncated)";
    }

    private Object sanitizeValue(String key, Object value, Set<String> sensitiveKeys) {
        if (value == null) {
            return null;
        }
        String normalizedKey = normalizeKey(key);
        if (sensitiveKeys.contains(normalizedKey)) {
            return "[MASKED length=" + String.valueOf(value).length() + "]";
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                nested.put(String.valueOf(entry.getKey()), sanitizeValue(String.valueOf(entry.getKey()), entry.getValue(), sensitiveKeys));
            }
            return nested;
        }
        if (value instanceof List<?> listValue) {
            List<Object> nested = new ArrayList<>(listValue.size());
            for (Object each : listValue) {
                nested.add(sanitizeValue(key, each, sensitiveKeys));
            }
            return nested;
        }
        if (value instanceof String str) {
            return truncateText(str);
        }
        return value;
    }

    private Set<String> buildSensitiveKeys(MCPTool tool) {
        Set<String> keys = new LinkedHashSet<>(DEFAULT_SENSITIVE_KEYS);
        if (tool != null && tool.getSensitiveParams() != null) {
            for (String key : tool.getSensitiveParams()) {
                if (StrUtil.isNotBlank(key)) {
                    keys.add(normalizeKey(key));
                }
            }
        }
        return keys;
    }

    private String normalizeKey(String key) {
        return StrUtil.blankToDefault(key, "")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }
}
