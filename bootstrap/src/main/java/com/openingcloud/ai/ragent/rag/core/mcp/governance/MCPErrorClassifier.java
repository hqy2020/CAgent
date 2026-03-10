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
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * MCP 错误分类器。
 */
@Component
public class MCPErrorClassifier {

    public boolean isRetryable(MCPResponse response) {
        if (response == null) {
            return false;
        }
        if (response.getErrorType() == MCPResponse.ErrorType.TIMEOUT
                || response.getErrorType() == MCPResponse.ErrorType.TRANSIENT) {
            return true;
        }
        String errorCode = normalize(response.getErrorCode());
        return errorCode.contains("TIMEOUT")
                || errorCode.contains("429")
                || errorCode.contains("503")
                || errorCode.contains("TRANSIENT")
                || errorCode.startsWith("BRIDGE_")
                || errorCode.startsWith("PROCESS_ERROR");
    }

    public boolean isCircuitBreakerEligible(MCPResponse response) {
        return isRetryable(response);
    }

    public MCPResponse.ErrorType resolveErrorType(String errorCode, MCPResponse.ErrorType fallbackType) {
        if (fallbackType != null) {
            return fallbackType;
        }
        String normalized = normalize(errorCode);
        if (normalized.contains("MISSING_PARAM")
                || normalized.contains("INVALID")
                || normalized.contains("PATTERN")) {
            return MCPResponse.ErrorType.VALIDATION;
        }
        if (normalized.contains("USER_ID")
                || normalized.contains("CONFIRM")
                || normalized.contains("RETRIEVAL_WRITE_BLOCKED")) {
            return MCPResponse.ErrorType.PERMISSION;
        }
        if (normalized.contains("SECURITY") || normalized.contains("PATH")) {
            return MCPResponse.ErrorType.SECURITY;
        }
        if (normalized.contains("TIMEOUT")) {
            return MCPResponse.ErrorType.TIMEOUT;
        }
        if (normalized.contains("429")
                || normalized.contains("503")
                || normalized.contains("TRANSIENT")
                || normalized.startsWith("BRIDGE_")
                || normalized.startsWith("PROCESS_ERROR")
                || normalized.startsWith("EXTERNAL_MCP_ERROR")) {
            return MCPResponse.ErrorType.TRANSIENT;
        }
        if (normalized.contains("CONFLICT")) {
            return MCPResponse.ErrorType.BUSINESS;
        }
        return MCPResponse.ErrorType.EXECUTION;
    }

    public MCPResponse.ErrorType resolveExceptionType(Throwable exception) {
        if (exception instanceof TimeoutException) {
            return MCPResponse.ErrorType.TIMEOUT;
        }
        if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
            return MCPResponse.ErrorType.VALIDATION;
        }
        if (exception instanceof SecurityException) {
            return MCPResponse.ErrorType.SECURITY;
        }
        return MCPResponse.ErrorType.EXECUTION;
    }

    public MCPResponse classifyCliFailure(String toolId, String stderr) {
        String message = StrUtil.blankToDefault(stderr, "工具执行失败").trim();
        if (message.startsWith("SECURITY_VIOLATION:")) {
            return MCPResponse.error(toolId, "SECURITY_VIOLATION", trimPrefix(message, "SECURITY_VIOLATION:"),
                    MCPResponse.ErrorType.SECURITY, false);
        }
        if (message.startsWith("WRITE_VERIFY_FAILED:")) {
            return MCPResponse.error(toolId, "EXECUTION_ERROR", trimPrefix(message, "WRITE_VERIFY_FAILED:"),
                    MCPResponse.ErrorType.EXECUTION, false);
        }
        if (message.startsWith("EXTERNAL_MCP_ERROR:")) {
            return MCPResponse.error(toolId, "EXTERNAL_MCP_ERROR", trimPrefix(message, "EXTERNAL_MCP_ERROR:"),
                    MCPResponse.ErrorType.TRANSIENT, true);
        }
        if (message.contains("超时") || normalize(message).contains("TIMEOUT")) {
            return MCPResponse.error(toolId, "TIMEOUT", message, MCPResponse.ErrorType.TIMEOUT, true);
        }
        if (message.contains("不存在")) {
            return MCPResponse.error(toolId, "RESOURCE_NOT_FOUND", message, MCPResponse.ErrorType.VALIDATION, false);
        }
        return MCPResponse.error(toolId, "EXECUTION_ERROR", message, resolveErrorType(message, null), false);
    }

    private String trimPrefix(String value, String prefix) {
        return StrUtil.trim(StrUtil.removePrefix(value, prefix));
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
    }
}
