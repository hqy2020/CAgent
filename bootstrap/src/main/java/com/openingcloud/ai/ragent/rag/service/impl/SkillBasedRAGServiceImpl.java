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

package com.openingcloud.ai.ragent.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.trace.RagTraceContext;
import com.openingcloud.ai.ragent.infra.chat.ReasoningTraceContext;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandle;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.rag.aop.ChatRateLimit;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.intent.ChitchatDetector;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemorySnapshot;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.dto.AgentStepPayload;
import com.openingcloud.ai.ragent.rag.dto.ReasoningTracePayload;
import com.openingcloud.ai.ragent.rag.enums.SSEEventType;
import com.openingcloud.ai.ragent.rag.exception.TaskCancelledException;
import com.openingcloud.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.openingcloud.ai.ragent.rag.service.ConversationService;
import com.openingcloud.ai.ragent.rag.service.SkillBasedRAGService;
import com.openingcloud.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.openingcloud.ai.ragent.rag.service.handler.StreamChatEventHandler;
import com.openingcloud.ai.ragent.rag.service.handler.StreamTaskManager;
import com.openingcloud.ai.ragent.rag.skill.SkillBasedRAGOrchestrator;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * Skill-Based RAG 对话服务实现（v4）
 * <p>
 * 精简流程：
 * 1. 会话初始化 + 记忆加载（复用 v3）
 * 2. 闲聊检测（复用 ChitchatDetector）
 * 3. SkillBasedRAGOrchestrator.execute()
 * 4. 消息持久化（复用 v3 StreamChatEventHandler）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBasedRAGServiceImpl implements SkillBasedRAGService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final ConversationService conversationService;
    private final StreamTaskManager taskManager;
    private final StreamCallbackFactory callbackFactory;
    private final SkillBasedRAGOrchestrator orchestrator;

    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        boolean isNewConversation = StrUtil.isBlank(conversationId);
        String actualConversationId = isNewConversation ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("Skill RAG v4: 开始对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);

        StreamChatEventHandler chatEventHandler =
                (StreamChatEventHandler) callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
        StreamCallback callback = chatEventHandler;
        CancellationToken token = taskManager.createToken(taskId);

        // 推理追踪上下文
        ReasoningTraceContext.setListener((stepLabel, messages, response, usage) -> {
            List<ReasoningTracePayload.PromptMessageVO> messageVOs = messages == null ? List.of()
                    : messages.stream()
                    .map(m -> new ReasoningTracePayload.PromptMessageVO(m.getRole().name().toLowerCase(), m.getContent()))
                    .toList();
            ReasoningTracePayload.TokenUsageVO usageVO = usage == null ? null
                    : new ReasoningTracePayload.TokenUsageVO(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
            chatEventHandler.emitReasoningTrace(new ReasoningTracePayload(stepLabel, stepLabel, messageVOs, response, usageVO));
        });

        try {
            String userId = UserContext.getUserId();

            // 创建会话
            if (isNewConversation) {
                conversationService.createOrUpdate(ConversationCreateRequest.builder()
                        .conversationId(actualConversationId)
                        .userId(userId)
                        .question(question)
                        .lastTime(new java.util.Date())
                        .build());
            }

            // 记忆加载
            ConversationMemorySnapshot memorySnapshot = memoryService.loadSnapshot(actualConversationId, userId);
            memoryService.append(actualConversationId, userId, ChatMessage.user(question));

            // 闲聊快筛
            if (ChitchatDetector.isChitchat(question)) {
                log.info("Skill RAG v4: 闲聊快筛命中, conversationId={}", actualConversationId);
                emitStep(emitter, 1, "闲聊检测", "SUCCESS", "识别为闲聊，直接回复。");
                StreamCancellationHandle handle = streamSystemResponse(question, callback);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            token.throwIfCancelled();

            // 执行 Skill-Based RAG 编排
            ReasoningTraceContext.setStepLabel("Skill RAG 编排");
            StreamCancellationHandle handle = orchestrator.execute(
                    SkillBasedRAGOrchestrator.SkillExecuteRequest.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .userId(userId)
                            .history(memorySnapshot.toHistoryMessages())
                            .emitter(emitter)
                            .callback(callback)
                            .token(token)
                            .build()
            );
            taskManager.bindHandle(taskId, handle);
        } catch (TaskCancelledException e) {
            log.info("Skill RAG v4: 任务被取消。conversationId={}, taskId={}", actualConversationId, taskId);
        } catch (Exception e) {
            log.error("Skill RAG v4: 对话启动失败。conversationId={}, taskId={}", actualConversationId, taskId, e);
            callback.onError(e);
        } finally {
            ReasoningTraceContext.clear();
        }
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    private StreamCancellationHandle streamSystemResponse(String question, StreamCallback callback) {
        String systemPrompt = promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(question));
        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(ragConfigProperties.getChatSystemTemperature())
                .topP(ragConfigProperties.getChatSystemTopP())
                .maxTokens(ragConfigProperties.getChatMaxTokensSystem())
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private void emitStep(SseEmitter emitter, int stepIndex, String type,
                           String status, String summary) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(SSEEventType.AGENT_STEP.value())
                    .data(new AgentStepPayload(
                            0, stepIndex, type, status, summary,
                            null, null, null, null, null, null, null
                    )));
        } catch (IOException e) {
            log.warn("发送 Skill RAG 步骤事件失败, stepIndex={}", stepIndex, e);
        }
    }
}
