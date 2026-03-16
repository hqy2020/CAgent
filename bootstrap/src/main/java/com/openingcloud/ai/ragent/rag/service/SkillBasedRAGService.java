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

package com.openingcloud.ai.ragent.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Skill-Based RAG 对话服务接口（v4）
 * <p>
 * AI 自主驱动的知识检索对话：
 * - Level 1：系统提示词注入 KB 元数据目录
 * - Level 2：提供检索工具，AI 自主决策搜什么、搜哪个库
 * - Level 3：搜索结果按需加载，仅在 AI 调用工具时拉取
 */
public interface SkillBasedRAGService {

    /**
     * 发起 Skill-Based 流式问答
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（可选，空时创建新会话）
     * @param emitter        SSE 发射器
     */
    void streamChat(String question, String conversationId, SseEmitter emitter);

    /**
     * 停止指定任务
     *
     * @param taskId 任务 ID
     */
    void stopTask(String taskId);
}
