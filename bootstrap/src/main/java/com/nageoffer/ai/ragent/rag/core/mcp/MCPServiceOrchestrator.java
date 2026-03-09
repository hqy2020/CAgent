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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPAuditRecorder;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPErrorClassifier;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPFallbackCache;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPFallbackResolver;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPMetricsRecorder;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPPayloadSanitizer;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPToolHealthStore;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPToolSecurityGuard;
import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP 服务协调器。
 */
@Slf4j
@Service
public class MCPServiceOrchestrator implements MCPService {

    private final MCPToolRegistry toolRegistry;
    private final Executor mcpBatchThreadPoolExecutor;
    private final Executor mcpExecutionThreadPoolExecutor;
    private final RagMcpExecutionProperties properties;
    private final MCPToolHealthStore healthStore;
    private final MCPErrorClassifier errorClassifier;
    private final MCPMetricsRecorder metricsRecorder;
    private final MCPFallbackResolver fallbackResolver;
    private final MCPToolSecurityGuard securityGuard;
    private final MCPAuditRecorder auditRecorder;

    @Autowired
    public MCPServiceOrchestrator(MCPToolRegistry toolRegistry,
                                  @Qualifier("mcpBatchThreadPoolExecutor") Executor mcpBatchThreadPoolExecutor,
                                  @Qualifier("mcpExecutionThreadPoolExecutor") Executor mcpExecutionThreadPoolExecutor,
                                  RagMcpExecutionProperties properties,
                                  MCPToolHealthStore healthStore,
                                  MCPErrorClassifier errorClassifier,
                                  MCPMetricsRecorder metricsRecorder,
                                  MCPFallbackResolver fallbackResolver,
                                  MCPToolSecurityGuard securityGuard,
                                  MCPAuditRecorder auditRecorder) {
        this.toolRegistry = toolRegistry;
        this.mcpBatchThreadPoolExecutor = mcpBatchThreadPoolExecutor;
        this.mcpExecutionThreadPoolExecutor = mcpExecutionThreadPoolExecutor;
        this.properties = properties;
        this.healthStore = healthStore;
        this.errorClassifier = errorClassifier;
        this.metricsRecorder = metricsRecorder;
        this.fallbackResolver = fallbackResolver;
        this.securityGuard = securityGuard;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 测试辅助构造，保留直接 new 的兼容路径。
     */
    public MCPServiceOrchestrator(MCPToolRegistry toolRegistry, Executor mcpBatchThreadPoolExecutor) {
        RagMcpExecutionProperties defaultProperties = new RagMcpExecutionProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        MCPPayloadSanitizer payloadSanitizer = new MCPPayloadSanitizer(defaultProperties);
        this.toolRegistry = toolRegistry;
        this.mcpBatchThreadPoolExecutor = mcpBatchThreadPoolExecutor;
        this.mcpExecutionThreadPoolExecutor = mcpBatchThreadPoolExecutor;
        this.properties = defaultProperties;
        this.healthStore = new MCPToolHealthStore(defaultProperties);
        this.errorClassifier = new MCPErrorClassifier();
        this.metricsRecorder = new MCPMetricsRecorder(new SimpleMeterRegistry());
        this.fallbackResolver = new MCPFallbackResolver(
                new MCPFallbackCache((StringRedisTemplate) null, objectMapper, defaultProperties)
        );
        this.securityGuard = new MCPToolSecurityGuard();
        this.auditRecorder = new MCPAuditRecorder(
                objectMapper,
                payloadSanitizer,
                new EmptyObjectProvider<>(),
                new EmptyObjectProvider<>()
        );
    }

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
            return finalizeAndRecord(
                    request,
                    null,
                    startTime,
                    MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId, MCPResponse.ErrorType.VALIDATION, false),
                    0,
                    0
            );
        }

        MCPToolExecutor executor = executorOpt.get();
        MCPTool tool = executor.getToolDefinition();

        MCPResponse securityError = securityGuard.validate(request, tool);
        if (securityError != null) {
            log.warn("MCP 工具安全校验失败, toolId: {}, requestId: {}, errorCode: {}",
                    toolId, request.getRequestId(), securityError.getErrorCode());
            return finalizeAndRecord(request, tool, startTime, securityError, 0, 0);
        }

        MCPResponse validationError = validateRequest(request, tool);
        if (validationError != null) {
            log.warn("MCP 工具执行前校验失败, toolId: {}, requestId: {}, errorCode: {}",
                    toolId, request.getRequestId(), validationError.getErrorCode());
            return finalizeAndRecord(request, tool, startTime, validationError, 0, 0);
        }

        if (!healthStore.allowCall(toolId)) {
            metricsRecorder.syncCircuitState(toolId, healthStore.currentState(toolId));
            MCPResponse circuitOpenResponse = buildErrorResponse(
                    toolId,
                    "CIRCUIT_OPEN",
                    StrUtil.blankToDefault(tool.getFallbackMessage(), "工具暂时不可用，请稍后重试。"),
                    MCPResponse.ErrorType.TRANSIENT,
                    true,
                    "SYSTEM_ERROR",
                    Map.of("toolId", toolId, "circuitState", healthStore.currentState(toolId)),
                    "系统暂时不可用，请稍后重试。"
            );
            return finalizeAndRecord(request, tool, startTime,
                    resolveFallbackIfNeeded(request, tool, circuitOpenResponse), 0, 0);
        }

        int maxAttempts = Math.max(1, tool.getMaxRetries() + 1);
        int retryCount = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            MCPResponse currentResponse;
            try {
                currentResponse = invokeWithTimeout(executor, request, tool);
            } catch (TimeoutException e) {
                currentResponse = MCPResponse.error(
                        toolId,
                        "TIMEOUT",
                        "工具调用超时: " + resolveTimeoutSeconds(tool) + "s",
                        MCPResponse.ErrorType.TIMEOUT,
                        true
                );
            } catch (Exception e) {
                log.error("MCP 工具执行异常, toolId: {}, requestId: {}, attempt: {}",
                        toolId, request.getRequestId(), attempt, e);
                currentResponse = buildExecutionExceptionResponse(toolId, e);
            }

            normalizeResponse(toolId, currentResponse);
            if (currentResponse.isSuccess()) {
                recordCircuitSuccess(toolId);
                fallbackResolver.cacheSuccess(request, tool, currentResponse);
                log.info("MCP 工具执行完成, toolId: {}, requestId: {}, attempt: {}, success: true",
                        toolId, request.getRequestId(), attempt);
                return finalizeAndRecord(request, tool, startTime, currentResponse, attempt, retryCount);
            }

            boolean retryable = currentResponse.isRetryable() || errorClassifier.isRetryable(currentResponse);
            currentResponse.setRetryable(retryable);
            if (errorClassifier.isCircuitBreakerEligible(currentResponse)) {
                recordCircuitFailure(toolId);
            } else {
                recordCircuitNeutral(toolId);
            }

            if (retryable && attempt < maxAttempts) {
                retryCount++;
                metricsRecorder.recordRetry(toolId, request.getRequestSource().name());
                log.warn("MCP 工具执行失败，准备重试, toolId: {}, requestId: {}, attempt: {}, errorCode: {}",
                        toolId, request.getRequestId(), attempt, currentResponse.getErrorCode());
                if (!pauseBeforeRetry(retryCount)) {
                    return finalizeAndRecord(request, tool, startTime,
                            resolveFallbackIfNeeded(request, tool, currentResponse), attempt, retryCount);
                }
                continue;
            }

            log.warn("MCP 工具执行失败, toolId: {}, requestId: {}, attempt: {}, errorCode: {}",
                    toolId, request.getRequestId(), attempt, currentResponse.getErrorCode());
            return finalizeAndRecord(request, tool, startTime,
                    resolveFallbackIfNeeded(request, tool, currentResponse), attempt, retryCount);
        }

        MCPResponse exhaustedResponse = MCPResponse.error(
                toolId,
                "PROCESS_ERROR",
                "工具调用失败，且已达到最大重试次数",
                MCPResponse.ErrorType.TRANSIENT,
                true
        );
        return finalizeAndRecord(request, tool, startTime,
                resolveFallbackIfNeeded(request, tool, exhaustedResponse), maxAttempts, retryCount);
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

        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> execute(request), mcpBatchThreadPoolExecutor))
                .toList();

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
        if (request.getRequestSource() == null) {
            request.setRequestSource(MCPRequestSource.DIRECT);
        }
        if (StrUtil.isBlank(request.getUserId())) {
            request.setUserId(UserContext.getUserId());
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
        CompletableFuture<MCPResponse> future = CompletableFuture.supplyAsync(
                () -> executor.execute(request),
                mcpExecutionThreadPoolExecutor
        );
        try {
            return future.get(resolveTimeoutSeconds(tool), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private int resolveTimeoutSeconds(MCPTool tool) {
        if (tool == null || tool.getTimeoutSeconds() <= 0) {
            return Math.max(1, properties.getDefaultTimeoutSeconds());
        }
        return tool.getTimeoutSeconds();
    }

    private void normalizeResponse(String toolId, MCPResponse response) {
        if (response == null) {
            throw new IllegalStateException("工具未返回响应: " + toolId);
        }
        if (StrUtil.isBlank(response.getToolId())) {
            response.setToolId(toolId);
        }
        if (!response.isSuccess()) {
            response.setErrorType(errorClassifier.resolveErrorType(response.getErrorCode(), response.getErrorType()));
            response.setRetryable(response.isRetryable() || errorClassifier.isRetryable(response));
            enrichErrorResponse(response);
        }
    }

    private MCPResponse finalizeAndRecord(MCPRequest request,
                                          MCPTool tool,
                                          long startTime,
                                          MCPResponse response,
                                          int attemptCount,
                                          int retryCount) {
        MCPResponse finalResponse = response == null
                ? MCPResponse.error(request.getToolId(), "EMPTY_RESPONSE", "工具未返回有效结果", MCPResponse.ErrorType.EXECUTION, false)
                : response;
        if (!finalResponse.isSuccess()) {
            enrichErrorResponse(finalResponse);
        }
        finalResponse.setCostMs(System.currentTimeMillis() - startTime);
        metricsRecorder.recordCall(request, finalResponse);
        auditRecorder.record(request, tool, finalResponse, startTime, attemptCount, retryCount,
                healthStore.currentState(request == null ? null : request.getToolId()));
        return finalResponse;
    }

    private MCPResponse resolveFallbackIfNeeded(MCPRequest request, MCPTool tool, MCPResponse response) {
        if (tool == null || response == null || tool.getOperationType() == MCPTool.OperationType.WRITE) {
            return response;
        }
        if (!response.isRetryable() && !"CIRCUIT_OPEN".equalsIgnoreCase(response.getErrorCode())) {
            return response;
        }
        return fallbackResolver.resolve(request, tool, response);
    }

    private void recordCircuitSuccess(String toolId) {
        String beforeState = healthStore.currentState(toolId);
        healthStore.markSuccess(toolId);
        syncCircuitMetrics(toolId, beforeState);
    }

    private void recordCircuitFailure(String toolId) {
        String beforeState = healthStore.currentState(toolId);
        healthStore.markFailure(toolId);
        syncCircuitMetrics(toolId, beforeState);
    }

    private void recordCircuitNeutral(String toolId) {
        String beforeState = healthStore.currentState(toolId);
        healthStore.markNeutral(toolId);
        syncCircuitMetrics(toolId, beforeState);
    }

    private void syncCircuitMetrics(String toolId, String beforeState) {
        String afterState = healthStore.currentState(toolId);
        if (!Objects.equals(beforeState, afterState)) {
            metricsRecorder.recordCircuitTransition(toolId, afterState);
        } else {
            metricsRecorder.syncCircuitState(toolId, afterState);
        }
    }

    private boolean pauseBeforeRetry(int retryCount) {
        long initialDelay = Math.max(1L, properties.getRetry().getInitialDelayMs());
        long maxDelay = Math.max(initialDelay, properties.getRetry().getMaxDelayMs());
        int exponent = Math.min(Math.max(0, retryCount - 1), 30);
        long delayMs = Math.min(initialDelay * (1L << exponent), maxDelay);
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private MCPResponse buildExecutionExceptionResponse(String toolId, Exception exception) {
        MCPResponse.ErrorType errorType = errorClassifier.resolveExceptionType(exception);
        String errorCode = switch (errorType) {
            case SECURITY -> "SECURITY_VIOLATION";
            case VALIDATION -> "INVALID_REQUEST";
            default -> "PROCESS_ERROR";
        };
        MCPResponse response = MCPResponse.error(
                toolId,
                errorCode,
                "工具调用异常: " + StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName()),
                errorType,
                false
        );
        response.setRetryable(errorClassifier.isRetryable(response));
        return response;
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
        String hint = "请将参数 " + paramName + " 设置为以下值之一：" + String.join("、", def.getEnumValues()) + "。";
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

    private static final class EmptyObjectProvider<T> implements ObjectProvider<T> {

        @Override
        public T getObject(Object... args) {
            return null;
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }

        @Override
        public T getObject() {
            return null;
        }
    }
}
