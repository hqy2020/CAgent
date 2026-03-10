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

package com.openingcloud.ai.ragent.rag.agent;

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequestSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 待确认写操作提案
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingProposal {

    private String proposalId;
    private String userId;
    private String conversationId;
    private String toolId;
    private String userQuestion;
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();
    private String targetPath;
    private String riskHint;
    private String status;
    private Long createdAt;
    private Long expiresAt;

    public MCPRequest toMcpRequest() {
        return MCPRequest.builder()
                .requestId(proposalId)
                .traceId(proposalId)
                .idempotencyKey(proposalId)
                .toolId(toolId)
                .userId(userId)
                .conversationId(conversationId)
                .userQuestion(userQuestion)
                .requestSource(MCPRequestSource.AGENT_CONFIRM)
                .confirmed(true)
                .parameters(parameters == null ? new HashMap<>() : new HashMap<>(parameters))
                .build();
    }
}
