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

import lombok.Builder;

import java.util.List;

/**
 * Agent 工作流执行结果
 *
 * @param reply       回复文本
 * @param changedFiles 变更文件列表（相对 vault 路径）
 * @param opsCount    变更操作数
 * @param warnings    告警或提醒
 */
@Builder
public record AgentWorkflowResult(
        String reply,
        List<String> changedFiles,
        int opsCount,
        List<String> warnings) {

    public static AgentWorkflowResult empty(String reply) {
        return AgentWorkflowResult.builder()
                .reply(reply)
                .changedFiles(List.of())
                .warnings(List.of())
                .opsCount(0)
                .build();
    }
}
