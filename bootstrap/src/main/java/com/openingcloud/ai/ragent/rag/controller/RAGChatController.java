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

package com.openingcloud.ai.ragent.rag.controller;

import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.openingcloud.ai.ragent.framework.web.Results;
import com.openingcloud.ai.ragent.rag.service.RAGChatService;
import com.openingcloud.ai.ragent.rag.service.SkillBasedRAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;
    private final SkillBasedRAGService skillBasedRAGService;

    /**
     * 发起 SSE 流式对话
     */
    @IdempotentSubmit(
            key = "T(com.openingcloud.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
        SseEmitter emitter = new SseEmitter(0L);
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }

    /**
     * Skill-Based RAG 流式对话（v4）
     * AI 自主驱动检索，按需调用工具
     */
    @IdempotentSubmit(
            key = "T(com.openingcloud.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v4/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatV4(@RequestParam String question,
                              @RequestParam(required = false) String conversationId) {
        SseEmitter emitter = new SseEmitter(0L);
        skillBasedRAGService.streamChat(question, conversationId, emitter);
        return emitter;
    }

    /**
     * 停止 v4 任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v4/stop")
    public Result<Void> stopV4(@RequestParam String taskId) {
        skillBasedRAGService.stopTask(taskId);
        return Results.success();
    }
}
