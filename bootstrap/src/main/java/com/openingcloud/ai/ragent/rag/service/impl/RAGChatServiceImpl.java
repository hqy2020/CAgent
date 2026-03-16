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

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.framework.context.LoginUser;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.framework.trace.RagTraceContext;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.chat.ModelInvocationMetadata;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.chat.ReasoningTraceContext;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandle;

import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.rag.agent.AgentCommandRouter;
import com.openingcloud.ai.ragent.rag.agent.AgentModeDecider;
import com.openingcloud.ai.ragent.rag.agent.AgentModeDecision;
import com.openingcloud.ai.ragent.rag.agent.AgentOrchestrator;
import com.openingcloud.ai.ragent.rag.aop.ChatRateLimit;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;


import com.openingcloud.ai.ragent.rag.core.usermemory.MemoryExtractionService;
import com.openingcloud.ai.ragent.rag.core.usermemory.MemoryRecallService;
import com.openingcloud.ai.ragent.rag.core.usermemory.PinnedMemoryDetector;
import com.openingcloud.ai.ragent.rag.core.usermemory.UserMemoryProperties;
import com.openingcloud.ai.ragent.rag.core.usermemory.UserMemoryService;
import com.openingcloud.ai.ragent.rag.core.usermemory.UserProfileService;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryType;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.RecalledMemory;
import com.openingcloud.ai.ragent.rag.core.usermemory.MemoryVectorStoreService;
import com.openingcloud.ai.ragent.rag.dao.entity.UserMemoryDO;
import com.openingcloud.ai.ragent.rag.dao.entity.UserProfileDO;


import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
import com.openingcloud.ai.ragent.rag.core.intent.IntentRouter;
import com.openingcloud.ai.ragent.rag.core.intent.RoutingDecision;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryPlan;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryPlanner;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemorySnapshot;
import com.openingcloud.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptContext;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.core.prompt.RAGPromptService;
import com.openingcloud.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.openingcloud.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.openingcloud.ai.ragent.rag.core.rewrite.RewriteResult;
import com.openingcloud.ai.ragent.rag.dto.AgentModelPayload;
import com.openingcloud.ai.ragent.rag.dto.AgentStepPayload;
import com.openingcloud.ai.ragent.rag.dto.IntentGroup;
import com.openingcloud.ai.ragent.rag.dto.ReasoningTracePayload;
import com.openingcloud.ai.ragent.rag.dto.ReferenceItem;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.exception.TaskCancelledException;
import com.openingcloud.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.openingcloud.ai.ragent.rag.enums.SSEEventType;
import com.openingcloud.ai.ragent.rag.service.ConversationService;
import com.openingcloud.ai.ragent.rag.service.RAGChatService;
import com.openingcloud.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.openingcloud.ai.ragent.rag.service.handler.StreamChatEventHandler;
import com.openingcloud.ai.ragent.rag.service.handler.StreamTaskManager;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 意图路由(闲聊检测+意图分类+歧义引导) -> 改写拆分(仅KB/MIXED) -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private static final String WEB_SEARCH_MCP_TOOL_ID = "web_search";
    private static final String WEB_NEWS_MCP_TOOL_ID = "web_news";
    private static final String WEB_REALTIME_MCP_TOOL_ID = "web_realtime";
    private static final int STEP_ANALYZE = 1;
    private static final int STEP_INTENT = 2;
    private static final int STEP_RETRIEVE = 3;
    private static final int STEP_SOURCE = 4;
    private static final int STEP_ANSWER = 5;

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final ConversationMemoryPlanner memoryPlanner;
    private final StreamTaskManager taskManager;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentRouter intentRouter;
    private final RetrievalEngine retrievalEngine;
    private final ConversationService conversationService;
    private final AgentCommandRouter agentCommandRouter;
    private final AgentModeDecider agentModeDecider;
    private final AgentOrchestrator agentOrchestrator;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final UserMemoryProperties userMemoryProperties;
    private final UserMemoryService userMemoryService;
    private final UserProfileService userProfileService;
    private final MemoryRecallService memoryRecallService;
    private final MemoryExtractionService memoryExtractionService;
    private final MemoryVectorStoreService memoryVectorStoreService;
    @org.springframework.beans.factory.annotation.Qualifier("memoryExtractionExecutor")
    private final java.util.concurrent.Executor memoryExtractionExecutor;

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

        StreamChatEventHandler chatEventHandler = (StreamChatEventHandler) callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
        StreamCallback callback = chatEventHandler;
        CancellationToken token = taskManager.createToken(taskId);

        // 设置推理过程追踪上下文
        ReasoningTraceContext.setListener((stepLabel, messages, response, usage) -> {
            List<ReasoningTracePayload.PromptMessageVO> messageVOs = messages == null ? List.of()
                    : messages.stream()
                    .map(m -> new ReasoningTracePayload.PromptMessageVO(m.getRole().name().toLowerCase(), m.getContent()))
                    .toList();
            ReasoningTracePayload.TokenUsageVO usageVO = usage == null ? null
                    : new ReasoningTracePayload.TokenUsageVO(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
            chatEventHandler.emitReasoningTrace(new ReasoningTracePayload(
                    stepLabel, stepLabel, messageVOs, response, usageVO
            ));
        });

        String userId = UserContext.getUserId();
        try {
            // 首次对话时创建会话（会触发标题生成）
            if (isNewConversation) {
                conversationService.createOrUpdate(ConversationCreateRequest.builder()
                        .conversationId(actualConversationId)
                        .userId(userId)
                        .question(question)
                        .lastTime(new java.util.Date())
                        .build());
            }

            ConversationMemorySnapshot memorySnapshot = memoryService.loadSnapshot(actualConversationId, userId);
            memoryService.append(actualConversationId, userId, ChatMessage.user(question));
            ConversationMemoryPlan memoryPlan = memoryPlanner.plan(memorySnapshot, question);

            // ===== 用户记忆系统 =====
            String profileFragment = "";
            String recalledMemoryFragment = "";
            if (Boolean.TRUE.equals(userMemoryProperties.getEnabled())) {
                // 加载画像
                try {
                    Long userIdLong = Long.valueOf(userId);
                    UserProfileDO profile = userProfileService.loadOrCreate(userIdLong);
                    profileFragment = userProfileService.formatForPrompt(profile);
                } catch (Exception e) {
                    log.warn("加载用户画像失败: userId={}", userId, e);
                }

                // 召回相关记忆
                try {
                    Long userIdLong = Long.valueOf(userId);
                    List<RecalledMemory> recalledMemories = memoryRecallService.recall(userIdLong, question);
                    recalledMemoryFragment = memoryRecallService.formatForPrompt(recalledMemories);
                } catch (Exception e) {
                    log.warn("召回用户记忆失败: userId={}", userId, e);
                }

                // 检测 Pinned 记忆指令
                PinnedMemoryDetector.PinnedDetectResult pinnedResult = PinnedMemoryDetector.detect(question);
                if (pinnedResult != null) {
                    try {
                        Long userIdLong = Long.valueOf(userId);
                        UserMemoryDO saved = userMemoryService.save(
                                userIdLong, MemoryType.PINNED, pinnedResult.content(),
                                actualConversationId, null);
                        memoryVectorStoreService.upsert(saved.getId(), userIdLong, saved.getContent());
                        // 发送 MEMORY_SAVED SSE 事件
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(SSEEventType.MEMORY_SAVED.value())
                                    .data(Map.of("content", pinnedResult.content())));
                        } catch (IOException e) {
                            log.warn("发送 MEMORY_SAVED 事件失败", e);
                        }
                    } catch (Exception e) {
                        log.warn("保存 Pinned 记忆失败: userId={}", userId, e);
                    }
                }
            }

            // 将画像和记忆片段暂存，供后续 Prompt 组装使用
            final String finalProfileFragment = profileFragment;
            final String finalRecalledMemoryFragment = recalledMemoryFragment;

            boolean handledByAgent = agentCommandRouter.tryRoute(
                    question,
                    actualConversationId,
                    userId,
                    taskId,
                    emitter,
                    callback,
                    token
            );
            if (handledByAgent) {
                return;
            }

            AtomicReference<String> answerCompletionSummary = new AtomicReference<>("回答生成完成。");
            StreamCallback stageCallback = wrapAnswerStage(emitter, callback, answerCompletionSummary);
            emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "RUNNING", "已接收问题，开始分析。");
            token.throwIfCancelled();

            // ===== Phase 1: 意图路由（闲聊检测 + LLM 意图分类 + 歧义引导）=====
            emitProgressStep(emitter, STEP_INTENT, "识别意图", "RUNNING", "正在识别知识库和联网意图。");
            ReasoningTraceContext.setStepLabel("意图识别");
            RoutingDecision routing = intentRouter.route(question, token);
            log.info("意图路由决策 path={}, conversationId={}", routing.path(), actualConversationId);

            // 闲聊快筛：跳过改写和检索，直接由模型回复
            if (routing.path() == RoutingDecision.RoutingPath.CHITCHAT) {
                emitProgressStep(emitter, STEP_INTENT, "识别意图", "SUCCESS", "识别为闲聊，直接回复。");
                emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "SUCCESS", "闲聊消息，跳过分析。");
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在生成回复。");
                StreamCancellationHandle handle = streamSystemResponse(question, stageCallback,
                        finalProfileFragment, finalRecalledMemoryFragment);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            // 系统交互：跳过改写和检索
            if (routing.path() == RoutingDecision.RoutingPath.SYSTEM) {
                emitProgressStep(emitter, STEP_INTENT, "识别意图", "SUCCESS", "识别为系统交互，直接回复。");
                emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "SUCCESS", "系统交互消息，跳过分析。");
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在生成回复。");
                StreamCancellationHandle handle = streamSystemResponse(question, stageCallback,
                        finalProfileFragment, finalRecalledMemoryFragment);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            // 歧义引导：返回澄清提示
            if (routing.path() == RoutingDecision.RoutingPath.CLARIFICATION) {
                emitProgressStep(emitter, STEP_INTENT, "识别意图", "SUCCESS", "检测到意图歧义，生成澄清提示。");
                emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "SUCCESS", "已完成问题分析。");
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在生成澄清提示。");
                stageCallback.onContent(routing.clarificationPrompt());
                stageCallback.onComplete();
                return;
            }

            List<SubQuestionIntent> subIntents = routing.subIntents();
            emitProgressStep(emitter, STEP_INTENT, "识别意图", "SUCCESS", buildIntentSummary(subIntents));

            // ===== Phase 2: Query 重写（仅 KNOWLEDGE / MIXED 路径）=====
            RewriteResult rewriteResult;
            if (routing.needsQueryRewrite()) {
                ReasoningTraceContext.setStepLabel("Query 重写");
                rewriteResult = queryRewriteService.rewriteWithSplit(question, memoryPlan.getRewriteHistory());
                int subQuestionCount = CollUtil.isEmpty(rewriteResult.subQuestions()) ? 1 : rewriteResult.subQuestions().size();
                emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "SUCCESS",
                        "已完成问题改写，拆解为 " + subQuestionCount + " 个子问题。");

                // 重写后可能产生子问题，基于子问题重新做意图识别
                if (CollUtil.isNotEmpty(rewriteResult.subQuestions()) && rewriteResult.subQuestions().size() > 1) {
                    ReasoningTraceContext.setStepLabel("子问题意图识别");
                    subIntents = intentResolver.resolve(rewriteResult, token);
                }
            } else {
                rewriteResult = RewriteResult.unchanged(question);
                emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "SUCCESS", "已完成问题分析，跳过改写。");
            }

            // ===== Phase 3: 检索（KB + MCP）=====
            token.throwIfCancelled();
            String retrievalStageType = resolveRetrievalStageType(subIntents);
            emitProgressStep(emitter, STEP_RETRIEVE, retrievalStageType, "RUNNING", "正在拉取相关上下文。");
            token.throwIfCancelled();
            RetrievalContext ctx;
            try {
                ctx = retrievalEngine.retrieve(
                        subIntents,
                        memoryPlan.getRetrievalTopK(),
                        memoryPlan.getRetrievalBudgetTokens(),
                        memoryPlan,
                        token
                );
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.error("知识库检索失败，降级为系统回答。conversationId={}, taskId={}", actualConversationId, taskId, e);
                emitProgressStep(emitter, STEP_RETRIEVE, retrievalStageType, "FAILED", "检索服务暂时不可用，降级为通用回答。");
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在生成降级回答。");
                stageCallback.onContent("知识库检索服务暂时不可用，以下回答将基于通用模型能力。\n\n");
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), stageCallback,
                        finalProfileFragment, finalRecalledMemoryFragment);
                taskManager.bindHandle(taskId, handle);
                return;
            }
            emitProgressStep(emitter, STEP_RETRIEVE, retrievalStageType, "SUCCESS", buildRetrievalSummary(ctx));
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
            // Keep original user intent for Agent-mode detection/planning.
            AgentModeDecision agentModeDecision = agentModeDecider.decide(
                    question,
                    subIntents,
                    ctx
            );
            if (agentModeDecision.enabled()) {
                log.info("进入 Agentic RAG 模式，reason={}, confidence={}, conversationId={}, taskId={}",
                        agentModeDecision.reason(), agentModeDecision.confidence(), actualConversationId, taskId);
                ReasoningTraceContext.setStepLabel("Agent 推理");
                boolean handled = agentOrchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                        .question(question)
                        .conversationId(actualConversationId)
                        .userId(userId)
                        .history(memoryPlan.getAnswerHistory())
                        .subIntents(subIntents)
                        .firstRoundContext(ctx)
                        .emitter(emitter)
                        .callback(callback)
                        .token(token)
                        .build());
                if (handled) {
                    return;
                }
                log.warn("Agentic RAG 执行未产出结果，回退到普通 RAG 流程。conversationId={}, taskId={}",
                        actualConversationId, taskId);
            }

            emitProgressStep(emitter, STEP_SOURCE, "整理来源", "RUNNING", "正在整理来源与参考片段。");
            // 发送参考文档引用事件（在 LLM 流式输出前）
            sendReferencesEvent(emitter, ctx);

            String webSearchPreface = buildWebSearchPreface(subIntents, ctx);
            if (StrUtil.isNotBlank(webSearchPreface)) {
                stageCallback.onContent(webSearchPreface + "\n\n");
            }
            emitProgressStep(emitter, STEP_SOURCE, "整理来源", "SUCCESS", buildSourceSummary(ctx));

            // 聚合所有意图用于 prompt 规划
            IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

            token.throwIfCancelled();
            emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在生成最终回答。");
            ReasoningTraceContext.setStepLabel("生成回答");
            StreamCancellationHandle handle = streamLLMResponse(
                    rewriteResult,
                    ctx,
                    mergedGroup,
                    memoryPlan.getAnswerHistory(),
                    thinkingEnabled,
                    stageCallback,
                    finalProfileFragment,
                    finalRecalledMemoryFragment
            );
            taskManager.bindHandle(taskId, handle);
        } catch (TaskCancelledException e) {
            log.info("任务被取消，提前退出。conversationId={}, taskId={}", actualConversationId, taskId);
        } catch (Exception e) {
            log.error("流式对话启动失败。conversationId={}, taskId={}", actualConversationId, taskId, e);
            callback.onError(e);
        } finally {
            ReasoningTraceContext.clear();
            // 异步提取用户记忆（insights + digest + 画像更新）
            if (Boolean.TRUE.equals(userMemoryProperties.getEnabled())) {
                final String extractUserId = userId;
                final String extractConversationId = actualConversationId;
                memoryExtractionExecutor.execute(() -> {
                    try {
                        memoryExtractionService.extractAndDigest(
                                Long.valueOf(extractUserId), extractConversationId, question);
                    } catch (Exception e) {
                        log.warn("异步记忆提取失败: userId={}, conversationId={}",
                                extractUserId, extractConversationId, e);
                    }
                });
            }
        }
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    private String buildWebSearchPreface(List<SubQuestionIntent> subIntents, RetrievalContext ctx) {
        if (ctx == null || ctx.hasKb() || StrUtil.isBlank(ctx.getMcpContext()) || CollUtil.isEmpty(subIntents)) {
            return null;
        }
        boolean hasWebSearch = false;
        for (SubQuestionIntent subIntent : subIntents) {
            if (subIntent == null || CollUtil.isEmpty(subIntent.nodeScores())) {
                continue;
            }
            for (NodeScore nodeScore : subIntent.nodeScores()) {
                if (nodeScore == null || nodeScore.getNode() == null || nodeScore.getNode().getKind() != IntentKind.MCP) {
                    continue;
                }
                String toolId = nodeScore.getNode().getMcpToolId();
                if (WEB_SEARCH_MCP_TOOL_ID.equals(toolId)) {
                    hasWebSearch = true;
                    continue;
                }
                if (StrUtil.isNotBlank(toolId)) {
                    return null;
                }
            }
        }
        if (!hasWebSearch) {
            return null;
        }

        String conclusion = extractContextLine(ctx.getMcpContext(), "可确认结论：");
        if (StrUtil.isBlank(conclusion)) {
            return null;
        }

        List<String> references = extractReferenceLines(ctx.getMcpContext(), 2);
        StringBuilder sb = new StringBuilder(conclusion);
        if (!references.isEmpty()) {
            sb.append("\n来源参考：\n");
            references.forEach(line -> sb.append(line).append("\n"));
        }
        return sb.toString().trim();
    }

    private String extractContextLine(String text, String prefix) {
        String normalized = StrUtil.blankToDefault(text, "");
        for (String line : normalized.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private List<String> extractReferenceLines(String text, int limit) {
        String normalized = StrUtil.blankToDefault(text, "");
        List<String> references = new ArrayList<>();
        boolean inReferenceBlock = false;
        for (String line : normalized.split("\\R")) {
            String trimmed = line.trim();
            if ("#### 引用来源".equals(trimmed)) {
                inReferenceBlock = true;
                continue;
            }
            if (inReferenceBlock && trimmed.startsWith("#### ")) {
                break;
            }
            if (inReferenceBlock && trimmed.startsWith("- ")) {
                references.add(trimmed);
                if (references.size() >= limit) {
                    break;
                }
            }
        }
        return references;
    }

    private StreamCancellationHandle streamSystemResponse(String question, StreamCallback callback) {
        return streamSystemResponse(question, callback, "", "");
    }

    private StreamCancellationHandle streamSystemResponse(String question, StreamCallback callback,
                                                           String profileFragment, String memoryFragment) {
        String systemPrompt = promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
        systemPrompt = appendMemoryFragments(systemPrompt, profileFragment, memoryFragment);
        ChatRequest req = ChatRequest.builder()
                .messages(buildDirectMessages(systemPrompt, List.of(), question))
                .temperature(ragConfigProperties.getChatSystemTemperature())
                .topP(ragConfigProperties.getChatSystemTopP())
                .maxTokens(ragConfigProperties.getChatMaxTokensSystem())
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private List<ChatMessage> buildDirectMessages(String systemPrompt,
                                                  List<ChatMessage> history,
                                                  String question) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));
        return messages;
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        return streamLLMResponse(rewriteResult, ctx, intentGroup, history, deepThinking, callback, "", "");
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback,
                                                       String profileFragment, String memoryFragment) {
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
                rewriteResult.originalQuestion(),
                rewriteResult.subQuestions()
        );

        // 将用户画像和记忆片段注入第一条 system 消息
        injectMemoryIntoMessages(messages, profileFragment, memoryFragment);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ragConfigProperties.getChatKbTemperature())
                .topP(ragConfigProperties.getChatKbTopP())
                .maxTokens(ragConfigProperties.getChatMaxTokensKb())
                .build();

        return llmService.streamChat(chatRequest, callback);
    }

    // ==================== 参考文档引用 ====================

    private void sendReferencesEvent(SseEmitter emitter, RetrievalContext ctx) {
        List<ReferenceItem> references = buildReferences(ctx);
        if (references.isEmpty()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(SSEEventType.REFERENCES.value())
                    .data(references));
        } catch (IOException e) {
            log.warn("发送 references SSE 事件失败", e);
        }
    }

    private List<ReferenceItem> buildReferences(RetrievalContext ctx) {
        Map<String, List<RetrievedChunk>> intentChunks = ctx.getIntentChunks();
        if (intentChunks == null || intentChunks.isEmpty()) {
            return List.of();
        }

        // 按 documentId 分组收集所有命中 chunks
        Map<String, List<RetrievedChunk>> chunksByDoc = new LinkedHashMap<>();
        for (List<RetrievedChunk> chunks : intentChunks.values()) {
            for (RetrievedChunk chunk : chunks) {
                if (chunk.getDocumentId() == null) {
                    continue;
                }
                chunksByDoc.computeIfAbsent(chunk.getDocumentId(), k -> new ArrayList<>()).add(chunk);
            }
        }

        if (chunksByDoc.isEmpty()) {
            return List.of();
        }

        // 每个文档的 chunks 按 score 降序排序
        for (List<RetrievedChunk> docChunks : chunksByDoc.values()) {
            docChunks.sort((a, b) -> {
                if (b.getScore() == null) return -1;
                if (a.getScore() == null) return 1;
                return b.getScore().compareTo(a.getScore());
            });
        }

        // 批量查询文档名和知识库名
        Set<String> docIds = chunksByDoc.keySet();
        Map<Long, KnowledgeDocumentDO> docMap = knowledgeDocumentMapper.selectBatchIds(
                docIds.stream().map(Long::valueOf).collect(Collectors.toList())
        ).stream().collect(Collectors.toMap(KnowledgeDocumentDO::getId, d -> d));

        Set<Long> kbIds = chunksByDoc.values().stream()
                .flatMap(List::stream)
                .map(RetrievedChunk::getKbId)
                .filter(id -> id != null)
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        Map<Long, KnowledgeBaseDO> kbMap = kbIds.isEmpty() ? Map.of() :
                knowledgeBaseMapper.selectBatchIds(new ArrayList<>(kbIds))
                        .stream().collect(Collectors.toMap(KnowledgeBaseDO::getId, kb -> kb));

        // 构建引用列表
        List<ReferenceItem> items = new ArrayList<>();
        for (Map.Entry<String, List<RetrievedChunk>> entry : chunksByDoc.entrySet()) {
            List<RetrievedChunk> docChunks = entry.getValue();
            RetrievedChunk bestChunk = docChunks.get(0);
            Long docIdLong = Long.valueOf(entry.getKey());
            KnowledgeDocumentDO doc = docMap.get(docIdLong);

            String docName = doc != null ? doc.getDocName() : null;
            String kbId = doc != null && doc.getKbId() != null
                    ? String.valueOf(doc.getKbId())
                    : bestChunk.getKbId();
            String kbName = null;
            if (StrUtil.isNotBlank(kbId)) {
                try {
                    KnowledgeBaseDO kb = kbMap.get(Long.valueOf(kbId));
                    kbName = kb != null ? kb.getName() : null;
                } catch (NumberFormatException ignored) {
                    // ignore invalid kbId
                }
            }

            String docUrl = resolveReferenceDocumentUrl(doc);
            String preview = bestChunk.getText();

            List<ReferenceItem.ChunkDetail> chunkDetails = docChunks.stream()
                    .map(c -> new ReferenceItem.ChunkDetail(c.getText(), c.getScore()))
                    .toList();

            items.add(new ReferenceItem(
                    entry.getKey(),
                    docName,
                    kbId,
                    kbName,
                    bestChunk.getScore(),
                    docUrl,
                    preview,
                    chunkDetails
            ));
        }
        return items;
    }

    private String resolveReferenceDocumentUrl(KnowledgeDocumentDO doc) {
        if (doc == null) {
            return null;
        }
        if (isHttpUrl(doc.getSourceLocation())) {
            return doc.getSourceLocation().trim();
        }
        if (isHttpUrl(doc.getFileUrl())) {
            return doc.getFileUrl().trim();
        }
        return null;
    }

    private boolean isHttpUrl(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    private StreamCallback wrapAnswerStage(SseEmitter emitter,
                                           StreamCallback delegate,
                                           AtomicReference<String> completionSummaryRef) {
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<AgentModelPayload> selectedModelRef = new AtomicReference<>(null);
        return new StreamCallback() {
            @Override
            public void onContent(String content) {
                delegate.onContent(content);
            }

            @Override
            public void onThinking(String content) {
                delegate.onThinking(content);
            }

            @Override
            public void onModelSelected(ModelInvocationMetadata model) {
                AgentModelPayload payload = toAgentModelPayload(model);
                selectedModelRef.set(payload);
                emitProgressStep(
                        emitter,
                        STEP_ANSWER,
                        "生成回答",
                        "RUNNING",
                        "正在生成最终回答。",
                        null,
                        null,
                        null,
                        "当前回答由命中模型实时生成。",
                        payload
                );
                delegate.onModelSelected(model);
            }

            @Override
            public void onComplete() {
                if (finished.compareAndSet(false, true)) {
                    emitProgressStep(
                            emitter,
                            STEP_ANSWER,
                            "生成回答",
                            "SUCCESS",
                            StrUtil.blankToDefault(completionSummaryRef.get(), "回答生成完成。"),
                            null,
                            null,
                            null,
                            null,
                            selectedModelRef.get()
                    );
                }
                delegate.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                if (finished.compareAndSet(false, true)) {
                    emitProgressStep(
                            emitter,
                            STEP_ANSWER,
                            "生成回答",
                            "FAILED",
                            buildStageErrorSummary(error),
                            null,
                            null,
                            null,
                            null,
                            selectedModelRef.get()
                    );
                }
                delegate.onError(error);
            }
        };
    }

    private void emitAnswerProgressStep(SseEmitter emitter,
                                        AtomicReference<String> completionSummaryRef,
                                        String status,
                                        String summary) {
        emitProgressStep(emitter, STEP_ANSWER, "生成回答", status, summary);
        if ("RUNNING".equalsIgnoreCase(status)) {
            completionSummaryRef.set(resolveAnswerCompletionSummary(summary));
        }
    }

    private String resolveAnswerCompletionSummary(String summary) {
        if (StrUtil.isBlank(summary)) {
            return "回答生成完成。";
        }
        if (summary.contains("澄清提示")) {
            return "澄清提示已生成。";
        }
        if (summary.contains("联网未命中结果")) {
            return "联网未命中结果，已回退到通用回答。";
        }
        if (summary.contains("整理联网结果")) {
            return "联网结果已整理完成。";
        }
        if (summary.contains("降级回答")) {
            return "降级回答已生成。";
        }
        if (summary.contains("通用回答")) {
            return "已切换为通用回答。";
        }
        if (summary.contains("兜底说明")) {
            return "已返回兜底说明。";
        }
        return "回答生成完成。";
    }

    private String buildIntentSummary(List<SubQuestionIntent> subIntents) {
        long subQuestionCount = subIntents == null ? 0 : subIntents.size();
        long mcpCount = subIntents == null ? 0 : subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .count();
        long kbCount = subIntents == null ? 0 : subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .filter(ns -> ns.getNode() != null
                        && (ns.getNode().getKind() == null || ns.getNode().getKind() == IntentKind.KB))
                .count();
        return "已识别 " + subQuestionCount + " 个子问题，联网意图 " + mcpCount + " 个，知识库意图 " + kbCount + " 个。";
    }

    private String resolveRetrievalStageType(List<SubQuestionIntent> subIntents) {
        boolean hasMcp = hasToolIntent(subIntents, WEB_SEARCH_MCP_TOOL_ID)
                || hasToolIntent(subIntents, WEB_NEWS_MCP_TOOL_ID)
                || hasToolIntent(subIntents, WEB_REALTIME_MCP_TOOL_ID);
        boolean hasKb = subIntents != null && subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .anyMatch(ns -> ns.getNode() != null
                        && (ns.getNode().getKind() == null || ns.getNode().getKind() == IntentKind.KB));
        if (hasMcp && hasKb) {
            return "联网/知识检索";
        }
        if (hasMcp) {
            return "联网查询";
        }
        return "知识检索";
    }

    private boolean hasToolIntent(List<SubQuestionIntent> subIntents, String toolId) {
        if (CollUtil.isEmpty(subIntents) || StrUtil.isBlank(toolId)) {
            return false;
        }
        return subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .anyMatch(ns -> ns.getNode() != null
                        && ns.getNode().getKind() == IntentKind.MCP
                        && toolId.equals(ns.getNode().getMcpToolId()));
    }

    private String buildRetrievalSummary(RetrievalContext ctx) {
        if (ctx == null) {
            return "检索阶段已完成。";
        }
        int kbCount = ctx.getIntentChunks() == null ? 0 : ctx.getIntentChunks().values().stream()
                .mapToInt(chunks -> chunks == null ? 0 : chunks.size())
                .sum();
        int mcpReferenceCount = countMcpSources(ctx.getMcpContext());
        if (ctx.hasKb() && ctx.hasMcp()) {
            return "已命中 " + kbCount + " 条知识片段，并整理 " + mcpReferenceCount + " 条联网来源。";
        }
        if (ctx.hasMcp()) {
            return "已整理 " + mcpReferenceCount + " 条联网来源。";
        }
        if (ctx.hasKb()) {
            return "已命中 " + kbCount + " 条知识片段。";
        }
        return "检索阶段未命中可用上下文。";
    }

    private String buildSourceSummary(RetrievalContext ctx) {
        int kbCount = ctx == null || ctx.getIntentChunks() == null ? 0 : ctx.getIntentChunks().values().stream()
                .mapToInt(chunks -> chunks == null ? 0 : chunks.size())
                .sum();
        int mcpReferenceCount = ctx == null ? 0 : countMcpSources(ctx.getMcpContext());
        if (mcpReferenceCount > 0 && kbCount > 0) {
            return "已整理 " + mcpReferenceCount + " 条联网来源与 " + kbCount + " 条知识片段。";
        }
        if (mcpReferenceCount > 0) {
            return "已整理 " + mcpReferenceCount + " 条联网来源。";
        }
        if (kbCount > 0) {
            return "已整理 " + kbCount + " 条知识片段。";
        }
        return "已完成来源整理。";
    }

    private int countMcpSources(String mcpContext) {
        int referenceBlockCount = extractReferenceLines(mcpContext, 20).size();
        if (referenceBlockCount > 0) {
            return referenceBlockCount;
        }
        String normalized = StrUtil.blankToDefault(mcpContext, "");
        int sourceLinkCount = 0;
        for (String line : normalized.split("\\R")) {
            if (line.trim().startsWith("来源链接：")) {
                sourceLinkCount++;
            }
        }
        return sourceLinkCount;
    }

    private String buildStageErrorSummary(Throwable error) {
        if (error == null || StrUtil.isBlank(error.getMessage())) {
            return "回答生成失败，请稍后重试。";
        }
        return "回答生成失败：" + error.getMessage();
    }

    private void emitProgressStep(SseEmitter emitter,
                                  int stepIndex,
                                  String type,
                                  String status,
                                  String summary) {
        emitProgressStep(emitter, stepIndex, type, status, summary, null, null, null, null, null);
    }

    private void emitProgressStep(SseEmitter emitter,
                                  int stepIndex,
                                  String type,
                                  String status,
                                  String summary,
                                  String instruction,
                                  String query,
                                  String toolId,
                                  String detail,
                                  AgentModelPayload model) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(SSEEventType.AGENT_STEP.value())
                    .data(new AgentStepPayload(
                            0,
                            stepIndex,
                            type,
                            status,
                            summary,
                            null,
                            null,
                            instruction,
                            query,
                            toolId,
                            detail,
                            model
                    )));
        } catch (IOException e) {
            log.warn("发送普通聊天阶段事件失败, stepIndex={}, type={}", stepIndex, type, e);
        }
    }

    private AgentModelPayload toAgentModelPayload(ModelInvocationMetadata model) {
        if (model == null) {
            return null;
        }
        return new AgentModelPayload(model.modelId(), model.provider());
    }

    // ==================== 用户记忆注入 ====================

    private String appendMemoryFragments(String systemPrompt, String profileFragment, String memoryFragment) {
        if (StrUtil.isBlank(profileFragment) && StrUtil.isBlank(memoryFragment)) {
            return systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt);
        if (StrUtil.isNotBlank(profileFragment)) {
            sb.append("\n\n").append(profileFragment);
        }
        if (StrUtil.isNotBlank(memoryFragment)) {
            sb.append("\n\n").append(memoryFragment);
        }
        return sb.toString();
    }

    private void injectMemoryIntoMessages(List<ChatMessage> messages, String profileFragment, String memoryFragment) {
        if (StrUtil.isBlank(profileFragment) && StrUtil.isBlank(memoryFragment)) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("system".equalsIgnoreCase(msg.getRole().name())) {
                String enhanced = appendMemoryFragments(msg.getContent(), profileFragment, memoryFragment);
                messages.set(i, ChatMessage.system(enhanced));
                return;
            }
        }
        // 没有 system 消息时，插入一条
        String fragment = appendMemoryFragments("", profileFragment, memoryFragment).trim();
        if (StrUtil.isNotBlank(fragment)) {
            messages.add(0, ChatMessage.system(fragment));
        }
    }
}
