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

package com.nageoffer.ai.ragent.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolCallAuditVO {

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
    private Date createTime;
}
