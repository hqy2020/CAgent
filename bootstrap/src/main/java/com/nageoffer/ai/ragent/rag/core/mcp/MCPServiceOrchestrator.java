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

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP 服务协调器
 * <p>
 * 提供 MCP 工具调用的核心逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MCPServiceOrchestrator implements MCPService {

    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_MILLIS = TimeUnit.SECONDS.toMillis(45);

    private final MCPToolRegistry toolRegistry;
    private final Executor mcpBatchThreadPoolExecutor;
    private final Map<String, CircuitBreakerState> circuitStates = new ConcurrentHashMap<>();

    @Override
    @RagTraceNode(name = "mcp-execute", type = "MCP")
    public MCPResponse execute(MCPRequest request) {
        if (request == null || request.getToolId() == null) {
            return MCPResponse.error(null, "INVALID_REQUEST", "请求参数无效", MCPResponse.ErrorType.VALIDATION, false);
        }

        prepareRequest(request);
        String toolId = request.getToolId();
        long startTime = System.currentTimeMillis();

        log.info("MCP 工具开始执行, toolId: {}, userId: {}, requestId: {}, traceId: {}",
                toolId, request.getUserId(), request.getRequestId(), request.getTraceId());

        Optional<MCPToolExecutor> executorOpt = toolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具执行失败, 工具不存在, toolId: {}", toolId);
            return finalizeResponse(
                    request,
                    startTime,
                    MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId, MCPResponse.ErrorType.VALIDATION, false)
            );
        }

        MCPToolExecutor executor = executorOpt.get();
        MCPTool tool = executor.getToolDefinition();
        MCPResponse validationError = validateRequest(request, tool);
        if (validationError != null) {
            log.warn("MCP 工具执行前校验失败, toolId: {}, requestId: {}, errorCode: {}",
                    toolId, request.getRequestId(), validationError.getErrorCode());
            return finalizeResponse(request, startTime, validationError);
        }

        if (isCircuitOpen(toolId)) {
            String message = StrUtil.blankToDefault(tool.getFallbackMessage(), "工具暂时不可用，请稍后重试。");
            return finalizeResponse(
                    request,
                    startTime,
                    MCPResponse.builder()
                            .success(false)
                            .toolId(toolId)
                            .errorCode("CIRCUIT_OPEN")
                            .errorMessage(message)
                            .errorType(MCPResponse.ErrorType.TRANSIENT)
                            .retryable(true)
                            .fallbackUsed(true)
                            .build()
            );
        }

        int maxAttempts = Math.max(1, tool.getMaxRetries() + 1);
        MCPResponse lastResponse = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                MCPResponse response = invokeWithTimeout(executor, request, tool);
                normalizeResponse(toolId, response);
                if (response.isSuccess()) {
                    resetCircuit(toolId);
                    log.info("MCP 工具执行完成, toolId: {}, requestId: {}, attempt: {}, success: true",
                            toolId, request.getRequestId(), attempt);
                    return finalizeResponse(request, startTime, response);
                }

                response.setErrorType(resolveErrorType(response.getErrorCode(), response.getErrorType()));
                response.setRetryable(response.isRetryable() || isRetryableResponse(response));
                lastResponse = response;
                if (response.isRetryable() && attempt < maxAttempts) {
                    log.warn("MCP 工具执行失败，准备重试, toolId: {}, requestId: {}, attempt: {}, errorCode: {}",
                            toolId, request.getRequestId(), attempt, response.getErrorCode());
                    continue;
                }
                recordFailure(toolId, response.isRetryable());
                log.warn("MCP 工具执行失败, toolId: {}, requestId: {}, attempt: {}, errorCode: {}",
                        toolId, request.getRequestId(), attempt, response.getErrorCode());
                return finalizeResponse(request, startTime, response);
            } catch (TimeoutException e) {
                lastResponse = MCPResponse.error(
                        toolId,
                        "TIMEOUT",
                        "工具调用超时: " + tool.getTimeoutSeconds() + "s",
                        MCPResponse.ErrorType.TIMEOUT,
                        true
                );
            } catch (Exception e) {
                log.error("MCP 工具执行异常, toolId: {}, requestId: {}, attempt: {}",
                        toolId, request.getRequestId(), attempt, e);
                lastResponse = MCPResponse.error(
                        toolId,
                        "EXECUTION_ERROR",
                        "工具调用异常: " + e.getMessage(),
                        resolveExceptionType(e),
                        false
                );
            }

            if (lastResponse == null || !lastResponse.isRetryable() || attempt >= maxAttempts) {
                recordFailure(toolId, lastResponse != null && lastResponse.isRetryable());
                return finalizeResponse(request, startTime, lastResponse);
            }
        }

        recordFailure(toolId, lastResponse != null && lastResponse.isRetryable());
        return finalizeResponse(request, startTime, lastResponse);
    }

    @Override
    @RagTraceNode(name = "mcp-execute-batch", type = "MCP")
    public List<MCPResponse> executeBatch(List<MCPRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        if (requests.size() > 1) {
            log.info("MCP 工具批量执行开始, 共 {} 个工具", requests.size());
        }

        // 并行执行所有请求
        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> execute(request), mcpBatchThreadPoolExecutor))
                .toList();

        // 等待所有任务完成并收集结果
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    @Override
    @RagTraceNode(name = "mcp-execute-batch-cancellable", type = "MCP")
    public List<MCPResponse> executeBatch(List<MCPRequest> requests, CancellationToken token) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        token.throwIfCancelled();

        if (requests.size() > 1) {
            log.info("MCP 工具批量执行开始（可取消），共 {} 个工具", requests.size());
        }

        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> {
                    token.throwIfCancelled();
                    return execute(request);
                }, mcpBatchThreadPoolExecutor))
                .toList();

        List<MCPResponse> results = new ArrayList<>(futures.size());
        for (CompletableFuture<MCPResponse> future : futures) {
            token.throwIfCancelled();
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof TaskCancelledException) {
                    throw (TaskCancelledException) e.getCause();
                }
                log.error("MCP 工具执行失败", e);
                throw e;
            }
        }
        return results;
    }

    @Override
    public List<MCPTool> listAvailableTools() {
        return toolRegistry.listAllTools();
    }

    @Override
    public boolean isToolAvailable(String toolId) {
        return toolRegistry.contains(toolId);
    }

    @Override
    public String buildToolsDescription() {
        List<MCPTool> tools = listAvailableTools().stream()
                .filter(MCPTool::isVisibleToModel)
                .toList();
        if (tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【可用工具列表】\n\n");

        for (MCPTool tool : tools) {
            sb.append(MCPToolDefinitionFormatter.format(tool)).append("\n");
        }

        return sb.toString().trim();
    }

    @Override
    public String mergeResponsesToText(List<MCPResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }

        List<String> successResults = new ArrayList<>();
        List<String> errorResults = new ArrayList<>();

        for (MCPResponse response : responses) {
            if (response.isSuccess() && response.getTextResult() != null) {
                successResults.add(response.getTextResult());
            } else if (!response.isSuccess()) {
                errorResults.add(String.format("工具 %s 调用失败: %s",
                        response.getToolId(), response.getErrorMessage()));
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!successResults.isEmpty()) {
            for (String result : successResults) {
                sb.append(result).append("\n\n");
            }
        }

        if (!errorResults.isEmpty()) {
            sb.append("【部分查询失败】\n");
            for (String error : errorResults) {
                sb.append("- ").append(error).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private void prepareRequest(MCPRequest request) {
        if (request.getParameters() == null) {
            request.setParameters(new LinkedHashMap<>());
        }
        if (StrUtil.isBlank(request.getRequestId())) {
            request.setRequestId(IdUtil.getSnowflakeNextIdStr());
        }
        if (StrUtil.isBlank(request.getTraceId())) {
            request.setTraceId(StrUtil.blankToDefault(RagTraceContext.getTraceId(), request.getRequestId()));
        }
        if (StrUtil.isBlank(request.getIdempotencyKey())) {
            String conversationId = StrUtil.blankToDefault(request.getConversationId(), "standalone");
            request.setIdempotencyKey(conversationId + ":" + request.getToolId() + ":" + request.getRequestId());
        }
    }

    private MCPResponse validateRequest(MCPRequest request, MCPTool tool) {
        if (tool.isRequireUserId() && StrUtil.isBlank(request.getUserId())) {
            return buildErrorResponse(
                    tool.getToolId(),
                    "USER_ID_REQUIRED",
                    "该工具需要用户身份信息",
                    MCPResponse.ErrorType.PERMISSION,
                    false,
                    "PERMISSION_DENIED",
                    Map.of("toolId", tool.getToolId()),
                    "请先完成登录或补充用户身份信息后重试。"
            );
        }
        if (tool.isConfirmationRequired() && !request.isConfirmed()) {
            return buildErrorResponse(
                    tool.getToolId(),
                    "CONFIRMATION_REQUIRED",
                    "该工具必须经确认链后执行",
                    MCPResponse.ErrorType.PERMISSION,
                    false,
                    "PERMISSION_DENIED",
                    Map.of("toolId", tool.getToolId()),
                    "该操作需要确认，请先走确认流程后重试。"
            );
        }
        if (tool.getParameters() == null || tool.getParameters().isEmpty()) {
            return null;
        }

        Map<String, Object> normalizedParameters = new LinkedHashMap<>(request.getParameters());
        for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
            String paramName = entry.getKey();
            MCPTool.ParameterDef def = entry.getValue();
            Object value = request.getParameters().get(paramName);
            if (isEmptyValue(value)) {
                value = null;
            }
            if (value == null && def.getDefaultValue() != null) {
                value = normalizeValue(def.getDefaultValue(), def.getType());
            }
            if (value == null) {
                normalizedParameters.remove(paramName);
                if (def.isRequired()) {
                    return buildMissingParamError(tool, paramName, def);
                }
                continue;
            }

            Object normalizedValue = normalizeValue(value, def.getType());
            if (normalizedValue == null) {
                return buildInvalidTypeError(tool, paramName, def, value);
            }

            Object alignedValue = alignEnumValue(def, normalizedValue);
            if (alignedValue == null) {
                return buildInvalidEnumError(tool, paramName, def, normalizedValue);
            }

            if (StrUtil.isNotBlank(def.getPattern())) {
                String candidate = String.valueOf(alignedValue);
                if (!Pattern.matches(def.getPattern(), candidate)) {
                    return buildPatternMismatchError(tool, paramName, def, candidate);
                }
            }

            normalizedParameters.put(paramName, alignedValue);
        }
        request.setParameters(normalizedParameters);
        return null;
    }

    private MCPResponse invokeWithTimeout(MCPToolExecutor executor, MCPRequest request, MCPTool tool) throws Exception {
        CompletableFuture<MCPResponse> future = CompletableFuture.supplyAsync(() -> executor.execute(request));
        try {
            return future.get(Math.max(1, tool.getTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private void normalizeResponse(String toolId, MCPResponse response) {
        if (response == null) {
            throw new IllegalStateException("工具未返回响应: " + toolId);
        }
        if (StrUtil.isBlank(response.getToolId())) {
            response.setToolId(toolId);
        }
        if (!response.isSuccess()) {
            response.setErrorType(resolveErrorType(response.getErrorCode(), response.getErrorType()));
            response.setRetryable(response.isRetryable() || isRetryableResponse(response));
            enrichErrorResponse(response);
        }
    }

    private MCPResponse finalizeResponse(MCPRequest request, long startTime, MCPResponse response) {
        MCPResponse finalResponse = response == null
                ? MCPResponse.error(request.getToolId(), "EMPTY_RESPONSE", "工具未返回有效结果", MCPResponse.ErrorType.EXECUTION, false)
                : response;
        if (!finalResponse.isSuccess()) {
            enrichErrorResponse(finalResponse);
        }
        finalResponse.setCostMs(System.currentTimeMillis() - startTime);
        return finalResponse;
    }

    private boolean isRetryableResponse(MCPResponse response) {
        if (response == null) {
            return false;
        }
        if (response.getErrorType() == MCPResponse.ErrorType.TIMEOUT || response.getErrorType() == MCPResponse.ErrorType.TRANSIENT) {
            return true;
        }
        String errorCode = StrUtil.blankToDefault(response.getErrorCode(), "").toUpperCase();
        return errorCode.contains("TIMEOUT")
                || errorCode.contains("429")
                || errorCode.contains("503")
                || errorCode.contains("TRANSIENT")
                || errorCode.contains("EXTENSION_NOT_CONNECTED")
                || errorCode.contains("BRIDGE")
                || errorCode.contains("PROCESS_ERROR");
    }

    private MCPResponse.ErrorType resolveErrorType(String errorCode, MCPResponse.ErrorType fallbackType) {
        if (fallbackType != null) {
            return fallbackType;
        }
        String normalized = StrUtil.blankToDefault(errorCode, "").toUpperCase();
        if (normalized.contains("MISSING_PARAM") || normalized.contains("INVALID") || normalized.contains("PATTERN")) {
            return MCPResponse.ErrorType.VALIDATION;
        }
        if (normalized.contains("USER_ID") || normalized.contains("CONFIRM")) {
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
                || normalized.contains("BRIDGE")
                || normalized.contains("PROCESS")) {
            return MCPResponse.ErrorType.TRANSIENT;
        }
        if (normalized.contains("CONFLICT")) {
            return MCPResponse.ErrorType.BUSINESS;
        }
        return MCPResponse.ErrorType.EXECUTION;
    }

    private void enrichErrorResponse(MCPResponse response) {
        if (response == null) {
            return;
        }
        String standardErrorCode = StrUtil.blankToDefault(
                response.getStandardErrorCode(),
                MCPResponse.inferStandardErrorCode(response.getErrorCode(), response.getErrorType())
        );
        response.setStandardErrorCode(standardErrorCode);
        if (response.getErrorDetails() == null) {
            response.setErrorDetails(new LinkedHashMap<>());
        }
        if (StrUtil.isBlank(response.getUserActionHint())) {
            response.setUserActionHint(MCPResponse.defaultUserActionHint(standardErrorCode, response.isRetryable()));
        }
    }

    private boolean isEmptyValue(Object value) {
        return value == null || (value instanceof String str && StrUtil.isBlank(str));
    }

    private Object normalizeValue(Object value, String type) {
        if (value == null) {
            return null;
        }
        String normalizedType = StrUtil.blankToDefault(type, "string").trim().toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "string" -> {
                String text = StrUtil.trim(String.valueOf(value));
                yield StrUtil.isBlank(text) ? null : text;
            }
            case "integer" -> convertToInteger(value);
            case "number" -> convertToNumber(value);
            case "boolean" -> convertToBoolean(value);
            case "array" -> convertToArray(value);
            case "object" -> value instanceof Map<?, ?> ? value : null;
            default -> value;
        };
    }

    private Integer convertToInteger(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric) || numeric % 1 != 0) {
                return null;
            }
            return (int) numeric;
        }
        String text = StrUtil.trim(String.valueOf(value));
        if (StrUtil.isBlank(text) || !text.matches("[-+]?\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Number convertToNumber(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
                return null;
            }
            return number;
        }
        String text = StrUtil.trim(String.valueOf(value));
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            if (text.matches("[-+]?\\d+")) {
                return Integer.parseInt(text);
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0D;
        }
        String text = StrUtil.trim(String.valueOf(value)).toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "y", "1", "是", "要", "开启", "需要" -> true;
            case "false", "no", "n", "0", "否", "不要", "关闭", "不需要" -> false;
            default -> null;
        };
    }

    private List<?> convertToArray(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        String text = StrUtil.trim(String.valueOf(value));
        if (StrUtil.isBlank(text)) {
            return null;
        }
        return List.of(text.split("[,，]")).stream()
                .map(StrUtil::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private Object alignEnumValue(MCPTool.ParameterDef def, Object value) {
        if (def.getEnumValues() == null || def.getEnumValues().isEmpty() || value == null) {
            return value;
        }
        String actual = StrUtil.trim(String.valueOf(value));
        for (String candidate : def.getEnumValues()) {
            if (StrUtil.equalsIgnoreCase(StrUtil.trim(candidate), actual)) {
                return candidate;
            }
        }
        return null;
    }

    private MCPResponse buildMissingParamError(MCPTool tool, String paramName, MCPTool.ParameterDef def) {
        String hint = "请补充参数 " + paramName + formatExampleOrPattern(def) + " 后重试。";
        return buildErrorResponse(
                tool.getToolId(),
                "MISSING_PARAM",
                "缺少必填参数: " + paramName,
                MCPResponse.ErrorType.VALIDATION,
                false,
                "INVALID_PARAMETER",
                buildParamDetails(paramName, def, null),
                hint
        );
    }

    private MCPResponse buildInvalidTypeError(MCPTool tool,
                                              String paramName,
                                              MCPTool.ParameterDef def,
                                              Object actualValue) {
        String hint = "请按 " + StrUtil.blankToDefault(def.getType(), "string") + " 类型填写参数 " + paramName
                + formatExampleOrPattern(def) + "。";
        return buildErrorResponse(
                tool.getToolId(),
                "INVALID_PARAM_TYPE",
                "参数 " + paramName + " 类型不正确: " + actualValue,
                MCPResponse.ErrorType.VALIDATION,
                false,
                "INVALID_PARAMETER",
                buildParamDetails(paramName, def, actualValue),
                hint
        );
    }

    private MCPResponse buildInvalidEnumError(MCPTool tool,
                                              String paramName,
                                              MCPTool.ParameterDef def,
                                              Object actualValue) {
        String hint = "请将参数 " + paramName + " 设置为以下值之一："
                + String.join("、", def.getEnumValues()) + "。";
        return buildErrorResponse(
                tool.getToolId(),
                "INVALID_PARAM_ENUM",
                "参数 " + paramName + " 不在允许范围内: " + actualValue,
                MCPResponse.ErrorType.VALIDATION,
                false,
                "INVALID_PARAMETER",
                buildParamDetails(paramName, def, actualValue),
                hint
        );
    }

    private MCPResponse buildPatternMismatchError(MCPTool tool,
                                                  String paramName,
                                                  MCPTool.ParameterDef def,
                                                  Object actualValue) {
        String hint = "请按约定格式填写参数 " + paramName + formatExampleOrPattern(def) + "。";
        return buildErrorResponse(
                tool.getToolId(),
                "PARAM_PATTERN_MISMATCH",
                "参数 " + paramName + " 格式不正确: " + actualValue,
                MCPResponse.ErrorType.VALIDATION,
                false,
                "INVALID_PARAMETER",
                buildParamDetails(paramName, def, actualValue),
                hint
        );
    }

    private Map<String, Object> buildParamDetails(String paramName, MCPTool.ParameterDef def, Object actualValue) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", paramName);
        details.put("description", def.getDescription());
        details.put("expectedType", StrUtil.blankToDefault(def.getType(), "string"));
        if (actualValue != null) {
            details.put("actualValue", actualValue);
        }
        if (def.getEnumValues() != null && !def.getEnumValues().isEmpty()) {
            details.put("allowedValues", def.getEnumValues());
        }
        if (def.getDefaultValue() != null) {
            details.put("defaultValue", def.getDefaultValue());
        }
        if (StrUtil.isNotBlank(def.getExample())) {
            details.put("example", def.getExample());
        }
        if (StrUtil.isNotBlank(def.getPattern())) {
            details.put("pattern", def.getPattern());
        }
        return details;
    }

    private String formatExampleOrPattern(MCPTool.ParameterDef def) {
        if (def == null) {
            return "";
        }
        if (StrUtil.isNotBlank(def.getExample())) {
            return "（示例: " + def.getExample() + "）";
        }
        if (StrUtil.isNotBlank(def.getPattern())) {
            return "（格式: " + def.getPattern() + "）";
        }
        return "";
    }

    private MCPResponse buildErrorResponse(String toolId,
                                           String errorCode,
                                           String errorMessage,
                                           MCPResponse.ErrorType errorType,
                                           boolean retryable,
                                           String standardErrorCode,
                                           Map<String, Object> errorDetails,
                                           String userActionHint) {
        return MCPResponse.builder()
                .success(false)
                .toolId(toolId)
                .errorCode(errorCode)
                .standardErrorCode(standardErrorCode)
                .errorMessage(errorMessage)
                .errorDetails(errorDetails == null ? new LinkedHashMap<>() : new LinkedHashMap<>(errorDetails))
                .userActionHint(userActionHint)
                .errorType(errorType)
                .retryable(retryable)
                .build();
    }

    private MCPResponse.ErrorType resolveExceptionType(Exception exception) {
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

    private boolean isCircuitOpen(String toolId) {
        CircuitBreakerState state = circuitStates.get(toolId);
        return state != null && state.isOpen();
    }

    private void resetCircuit(String toolId) {
        circuitStates.remove(toolId);
    }

    private void recordFailure(String toolId, boolean transientFailure) {
        if (!transientFailure) {
            circuitStates.remove(toolId);
            return;
        }
        circuitStates.compute(toolId, (key, state) -> {
            CircuitBreakerState next = state == null ? new CircuitBreakerState() : state;
            next.failureCount++;
            if (next.failureCount >= CIRCUIT_FAILURE_THRESHOLD) {
                next.openUntilEpochMs = System.currentTimeMillis() + CIRCUIT_OPEN_MILLIS;
            }
            return next;
        });
    }

    private static final class CircuitBreakerState {
        private int failureCount;
        private long openUntilEpochMs;

        private boolean isOpen() {
            if (openUntilEpochMs <= 0L) {
                return false;
            }
            if (System.currentTimeMillis() >= openUntilEpochMs) {
                failureCount = 0;
                openUntilEpochMs = 0L;
                return false;
            }
            return true;
        }
    }
}
