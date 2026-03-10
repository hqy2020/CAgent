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

package com.openingcloud.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MCP 工具调用审计记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_mcp_tool_call_audit")
public class MCPToolCallAuditDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private String requestId;

    private String idempotencyKey;

    private String conversationId;

    private String userId;

    private String requestSource;

    private String toolId;

    private String toolName;

    private String operationType;

    private String sensitivity;

    private Boolean success;

    private Boolean fallbackUsed;

    private Integer attemptCount;

    private Integer retryCount;

    private String circuitState;

    private String standardErrorCode;

    private String errorCode;

    private String errorMessage;

    private String requestPayloadJson;

    private String responsePayloadJson;

    private Long durationMs;

    private Date startTime;

    private Date endTime;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
