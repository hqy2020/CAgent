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

import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import lombok.Builder;

/**
 * Agent 工作流执行上下文
 *
 * @param command        命令
 * @param userId         当前用户ID
 * @param conversationId 会话ID
 * @param taskId         任务ID
 * @param token          取消令牌
 */
@Builder
public record AgentWorkflowContext(
        AgentCommand command,
        String userId,
        String conversationId,
        String taskId,
        CancellationToken token) {
}
