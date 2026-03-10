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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.trace.RagTraceContext;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.dao.entity.MCPToolCallAuditDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.MCPToolCallAuditMapper;
import com.openingcloud.ai.ragent.rag.service.RagTraceRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 调用审计记录器。
 */
@Slf4j
@Component
public class MCPAuditRecorder {

    private final ObjectMapper objectMapper;
    private final MCPPayloadSanitizer payloadSanitizer;
    private final MCPToolCallAuditMapper auditMapper;
    private final RagTraceRecordService ragTraceRecordService;

    @Autowired
    public MCPAuditRecorder(ObjectMapper objectMapper,
                            MCPPayloadSanitizer payloadSanitizer,
                            ObjectProvider<MCPToolCallAuditMapper> auditMapperProvider,
                            ObjectProvider<RagTraceRecordService> ragTraceRecordServiceProvider) {
        this.objectMapper = objectMapper;
        this.payloadSanitizer = payloadSanitizer;
        this.auditMapper = auditMapperProvider.getIfAvailable();
        this.ragTraceRecordService = ragTraceRecordServiceProvider.getIfAvailable();
    }

    public MCPAuditRecorder(ObjectMapper objectMapper,
                            MCPPayloadSanitizer payloadSanitizer,
                            MCPToolCallAuditMapper auditMapper,
                            RagTraceRecordService ragTraceRecordService) {
        this.objectMapper = objectMapper;
        this.payloadSanitizer = payloadSanitizer;
        this.auditMapper = auditMapper;
        this.ragTraceRecordService = ragTraceRecordService;
    }

    public void record(MCPRequest request,
                       MCPTool tool,
                       MCPResponse response,
                       long startTimeMs,
                       int attemptCount,
                       int retryCount,
                       String circuitState) {
        Date startTime = new Date(startTimeMs);
        Date endTime = new Date();
        Map<String, Object> requestPayload = payloadSanitizer.sanitizeRequest(request, tool);
        Map<String, Object> responsePayload = payloadSanitizer.sanitizeResponse(response, tool);

        if (auditMapper != null) {
            try {
                auditMapper.insert(MCPToolCallAuditDO.builder()
                        .traceId(request == null ? null : request.getTraceId())
                        .requestId(request == null ? null : request.getRequestId())
                        .idempotencyKey(request == null ? null : request.getIdempotencyKey())
                        .conversationId(request == null ? null : request.getConversationId())
                        .userId(request == null ? null : request.getUserId())
                        .requestSource(request == null || request.getRequestSource() == null ? null : request.getRequestSource().name())
                        .toolId(tool == null ? null : tool.getToolId())
                        .toolName(tool == null ? null : tool.getName())
                        .operationType(tool == null || tool.getOperationType() == null ? null : tool.getOperationType().name())
                        .sensitivity(tool == null || tool.getSensitivity() == null ? null : tool.getSensitivity().name())
                        .success(response != null && response.isSuccess())
                        .fallbackUsed(response != null && response.isFallbackUsed())
                        .attemptCount(attemptCount)
                        .retryCount(retryCount)
                        .circuitState(circuitState)
                        .standardErrorCode(response == null ? null : response.getStandardErrorCode())
                        .errorCode(response == null ? null : response.getErrorCode())
                        .errorMessage(payloadSanitizer.truncateText(response == null ? null : response.getErrorMessage()))
                        .requestPayloadJson(toJson(requestPayload))
                        .responsePayloadJson(toJson(responsePayload))
                        .durationMs(response == null ? 0L : response.getCostMs())
                        .startTime(startTime)
                        .endTime(endTime)
                        .build());
            } catch (Exception e) {
                log.warn("MCP 审计落库失败，已跳过，不影响主流程。toolId={}",
                        tool == null ? null : tool.getToolId(), e);
            }
        }

        if (ragTraceRecordService != null && request != null && request.getTraceId() != null) {
            String nodeId = RagTraceContext.currentNodeId();
            if (nodeId != null) {
                Map<String, Object> extraData = new LinkedHashMap<>();
                extraData.put("toolId", tool == null ? request.getToolId() : tool.getToolId());
                extraData.put("requestSource", request.getRequestSource());
                extraData.put("attemptCount", attemptCount);
                extraData.put("retryCount", retryCount);
                extraData.put("fallbackUsed", response != null && response.isFallbackUsed());
                extraData.put("circuitState", circuitState);
                extraData.put("standardErrorCode", response == null ? null : response.getStandardErrorCode());
                extraData.put("requestPayload", requestPayload);
                extraData.put("responsePayload", responsePayload);
                try {
                    ragTraceRecordService.updateNodeExtraData(request.getTraceId(), nodeId, extraData);
                } catch (Exception e) {
                    log.warn("MCP Trace 扩展数据记录失败，已跳过，不影响主流程。toolId={}, traceId={}, nodeId={}",
                            tool == null ? null : tool.getToolId(), request.getTraceId(), nodeId, e);
                }
            }
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return payloadSanitizer.truncateText(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            return payloadSanitizer.truncateText(String.valueOf(payload));
        }
    }
}
