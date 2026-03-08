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

package com.nageoffer.ai.ragent.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.admin.controller.vo.MCPToolCallAuditVO;
import com.nageoffer.ai.ragent.rag.dao.entity.MCPToolCallAuditDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.MCPToolCallAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

@Service
@RequiredArgsConstructor
public class MCPToolAuditAdminService {

    private final MCPToolCallAuditMapper auditMapper;

    public IPage<MCPToolCallAuditVO> pageAudits(String toolId,
                                                Boolean success,
                                                Boolean fallbackUsed,
                                                LocalDateTime startTimeFrom,
                                                LocalDateTime startTimeTo,
                                                long pageNo,
                                                long pageSize) {
        Page<MCPToolCallAuditDO> page = new Page<>(pageNo, pageSize);
        IPage<MCPToolCallAuditDO> result = auditMapper.selectPage(page, lambdaQuery(MCPToolCallAuditDO.class)
                .eq(toolId != null && !toolId.isBlank(), MCPToolCallAuditDO::getToolId, toolId)
                .eq(success != null, MCPToolCallAuditDO::getSuccess, success)
                .eq(fallbackUsed != null, MCPToolCallAuditDO::getFallbackUsed, fallbackUsed)
                .ge(startTimeFrom != null, MCPToolCallAuditDO::getStartTime, toDateTime(startTimeFrom))
                .le(startTimeTo != null, MCPToolCallAuditDO::getStartTime, toDateTime(startTimeTo))
                .orderByDesc(MCPToolCallAuditDO::getStartTime)
                .orderByDesc(MCPToolCallAuditDO::getId));
        Page<MCPToolCallAuditVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    public MCPToolCallAuditVO getAudit(Long id) {
        MCPToolCallAuditDO audit = auditMapper.selectById(id);
        return audit == null ? null : toVO(audit);
    }

    private java.util.Date toDateTime(LocalDateTime localDateTime) {
        return java.util.Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    private MCPToolCallAuditVO toVO(MCPToolCallAuditDO audit) {
        return MCPToolCallAuditVO.builder()
                .id(audit.getId())
                .traceId(audit.getTraceId())
                .requestId(audit.getRequestId())
                .idempotencyKey(audit.getIdempotencyKey())
                .conversationId(audit.getConversationId())
                .userId(audit.getUserId())
                .requestSource(audit.getRequestSource())
                .toolId(audit.getToolId())
                .toolName(audit.getToolName())
                .operationType(audit.getOperationType())
                .sensitivity(audit.getSensitivity())
                .success(audit.getSuccess())
                .fallbackUsed(audit.getFallbackUsed())
                .attemptCount(audit.getAttemptCount())
                .retryCount(audit.getRetryCount())
                .circuitState(audit.getCircuitState())
                .standardErrorCode(audit.getStandardErrorCode())
                .errorCode(audit.getErrorCode())
                .errorMessage(audit.getErrorMessage())
                .requestPayloadJson(audit.getRequestPayloadJson())
                .responsePayloadJson(audit.getResponsePayloadJson())
                .durationMs(audit.getDurationMs())
                .startTime(audit.getStartTime())
                .endTime(audit.getEndTime())
                .createTime(audit.getCreateTime())
                .build();
    }
}
