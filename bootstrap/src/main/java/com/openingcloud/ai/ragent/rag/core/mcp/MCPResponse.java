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

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 调用响应
 * <p>
 * 封装 MCP 工具调用的返回结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPResponse {

    public enum ErrorType {
        VALIDATION,
        PERMISSION,
        SECURITY,
        TIMEOUT,
        TRANSIENT,
        BUSINESS,
        EXECUTION
    }

    /**
     * 是否调用成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 工具 ID
     */
    private String toolId;

    /**
     * 结果数据（结构化）
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * 文本形式的结果（用于直接拼接到 Prompt）
     */
    private String textResult;

    /**
     * 错误信息（调用失败时）
     */
    private String errorMessage;

    /**
     * 错误码（调用失败时）
     */
    private String errorCode;

    /**
     * 标准化错误码
     */
    private String standardErrorCode;

    /**
     * 结构化错误细节
     */
    @Builder.Default
    private Map<String, Object> errorDetails = new HashMap<>();

    /**
     * 面向用户/模型的修复建议
     */
    private String userActionHint;

    /**
     * 错误类别（结构化）
     */
    private ErrorType errorType;

    /**
     * 是否可重试
     */
    @Builder.Default
    private boolean retryable = false;

    /**
     * 是否使用了降级结果
     */
    @Builder.Default
    private boolean fallbackUsed = false;

    /**
     * 调用耗时（毫秒）
     */
    private long costMs;

    /**
     * 创建成功响应
     */
    public static MCPResponse success(String toolId, String textResult) {
        return MCPResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .build();
    }

    /**
     * 创建成功响应（带结构化数据）
     */
    public static MCPResponse success(String toolId, String textResult, Map<String, Object> data) {
        return MCPResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .data(data)
                .build();
    }

    /**
     * 创建失败响应
     */
    public static MCPResponse error(String toolId, String errorCode, String errorMessage) {
        return error(toolId, errorCode, errorMessage, ErrorType.EXECUTION, false);
    }

    /**
     * 创建失败响应（带结构化错误属性）
     */
    public static MCPResponse error(String toolId,
                                    String errorCode,
                                    String errorMessage,
                                    ErrorType errorType,
                                    boolean retryable) {
        String standardErrorCode = inferStandardErrorCode(errorCode, errorType);
        return MCPResponse.builder()
                .success(false)
                .toolId(toolId)
                .errorCode(errorCode)
                .standardErrorCode(standardErrorCode)
                .errorMessage(errorMessage)
                .userActionHint(defaultUserActionHint(standardErrorCode, retryable))
                .errorType(errorType)
                .retryable(retryable)
                .build();
    }

    public static String inferStandardErrorCode(String errorCode, ErrorType errorType) {
        String normalizedCode = StrUtil.blankToDefault(errorCode, "").toUpperCase();
        if (normalizedCode.contains("MISSING_PARAM")
                || normalizedCode.contains("INVALID")
                || normalizedCode.contains("PATTERN")) {
            return "INVALID_PARAMETER";
        }
        if (normalizedCode.contains("USER_ID") || normalizedCode.contains("CONFIRM")
                || errorType == ErrorType.PERMISSION || errorType == ErrorType.SECURITY) {
            return "PERMISSION_DENIED";
        }
        if (normalizedCode.contains("NOT_FOUND")) {
            return "RESOURCE_NOT_FOUND";
        }
        if (normalizedCode.contains("DATE_CONFLICT")
                || normalizedCode.contains("CONFLICT")
                || errorType == ErrorType.BUSINESS) {
            return "BUSINESS_ERROR";
        }
        if (normalizedCode.contains("429")
                || normalizedCode.contains("RATE_LIMIT")
                || normalizedCode.contains("TOO_MANY_REQUESTS")) {
            return "RATE_LIMIT_EXCEEDED";
        }
        return "SYSTEM_ERROR";
    }

    public static String defaultUserActionHint(String standardErrorCode, boolean retryable) {
        String normalized = StrUtil.blankToDefault(standardErrorCode, "SYSTEM_ERROR").toUpperCase();
        return switch (normalized) {
            case "INVALID_PARAMETER" -> "请检查并补全参数后重试。";
            case "PERMISSION_DENIED" -> "请先完成确认或补充身份信息后重试。";
            case "RESOURCE_NOT_FOUND" -> "请确认目标资源或工具是否存在。";
            case "BUSINESS_ERROR" -> "请根据提示调整请求后重试。";
            case "RATE_LIMIT_EXCEEDED" -> "请求过于频繁，请稍后重试。";
            default -> retryable ? "系统暂时不可用，请稍后重试。" : "系统暂时不可用，请稍后再试。";
        };
    }
}
