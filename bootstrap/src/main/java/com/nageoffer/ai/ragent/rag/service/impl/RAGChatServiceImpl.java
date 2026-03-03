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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;
import com.nageoffer.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private static final int CHAT_MAX_TOKENS = 1600;

    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final ConversationService conversationService;

    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        boolean isNewConversation = StrUtil.isBlank(conversationId);
        String actualConversationId = isNewConversation ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
        CancellationToken token = taskManager.createToken(taskId);

        try {
            String userId = UserContext.getUserId();

            // 首次对话时创建会话（会触发标题生成）
            if (isNewConversation) {
                conversationService.createOrUpdate(ConversationCreateRequest.builder()
                        .conversationId(actualConversationId)
                        .userId(userId)
                        .question(question)
                        .lastTime(new java.util.Date())
                        .build());
            }

            List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));

            token.throwIfCancelled();
            RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);

            token.throwIfCancelled();
            List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult, token);

            token.throwIfCancelled();
            GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
            if (guidanceDecision.isPrompt()) {
                callback.onContent(guidanceDecision.getPrompt());
                callback.onComplete();
                return;
            }

            boolean allSystemOnly = subIntents.stream()
                    .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
            if (allSystemOnly) {
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), callback);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            token.throwIfCancelled();
            RetrievalContext ctx;
            try {
                ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K, token);
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.error("知识库检索失败，降级为系统回答。conversationId={}, taskId={}", actualConversationId, taskId, e);
                callback.onContent("知识库检索服务暂时不可用，以下回答将基于通用模型能力。\n\n");
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), callback);
                taskManager.bindHandle(taskId, handle);
                return;
            }
            log.info("RetrievalContext isEmpty={}, hasKb={}, hasMcp={}, kbLen={}, mcpLen={}",
                    ctx.isEmpty(), ctx.hasKb(), ctx.hasMcp(),
                    ctx.getKbContext() != null ? ctx.getKbContext().length() : -1,
                    ctx.getMcpContext() != null ? ctx.getMcpContext().length() : -1);
            if (ctx.hasKb()) {
                String preview = ctx.getKbContext().length() > 500
                        ? ctx.getKbContext().substring(0, 500) + "..."
                        : ctx.getKbContext();
                log.info("kbContext preview:\n{}", preview);
            }
            if (ctx.isEmpty()) {
                String emptyReply = "未检索到与问题相关的文档内容。";
                callback.onContent(emptyReply);
                callback.onComplete();
                return;
            }

            // 聚合所有意图用于 prompt 规划
            IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

            token.throwIfCancelled();
            StreamCancellationHandle handle = streamLLMResponse(
                    rewriteResult,
                    ctx,
                    mergedGroup,
                    history,
                    thinkingEnabled,
                    callback
            );
            taskManager.bindHandle(taskId, handle);
        } catch (TaskCancelledException e) {
            log.info("任务被取消，提前退出。conversationId={}, taskId={}", actualConversationId, taskId);
        } catch (Exception e) {
            log.error("流式对话启动失败。conversationId={}, taskId={}", actualConversationId, taskId, e);
            callback.onError(e);
        }
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, StreamCallback callback) {
        String systemPrompt = promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.7D)
                .topP(0.8D)
                .maxTokens(CHAT_MAX_TOKENS)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .maxTokens(CHAT_MAX_TOKENS)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
