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

package com.openingcloud.ai.ragent.rag.enums;

import lombok.RequiredArgsConstructor;

/**
 * SSE 事件类型枚举
 */
@RequiredArgsConstructor
public enum SSEEventType {

    /**
     * 会话与任务的元信息事件
     */
    META("meta"),

    /**
     * 增量消息事件
     */
    MESSAGE("message"),

    /**
     * 参考文档引用事件
     */
    REFERENCES("references"),

    /**
     * Agent 工作流摘要事件
     */
    WORKFLOW("workflow"),

    /**
     * Agent 观察事件
     */
    AGENT_OBSERVE("agent_observe"),

    /**
     * Agent 规划事件
     */
    AGENT_PLAN("agent_plan"),

    /**
     * Agent 步骤执行事件
     */
    AGENT_STEP("agent_step"),

    /**
     * Agent 重规划事件
     */
    AGENT_REPLAN("agent_replan"),

    /**
     * Agent 写操作确认事件
     */
    AGENT_CONFIRM_REQUIRED("agent_confirm_required"),

    /**
     * 模型回复完成事件
     */
    FINISH("finish"),

    /**
     * 完成事件
     */
    DONE("done"),

    /**
     * 取消事件
     */
    CANCEL("cancel"),

    /**
     * 排队状态推送事件
     */
    QUEUE("queue"),

    /**
     * 拒绝事件
     */
    REJECT("reject"),

    /**
     * 错误事件
     */
    ERROR("error");

    private final String value;

    /**
     * SSE 事件名称（与前端约定一致）
     */
    public String value() {
        return value;
    }
}
