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
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
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
import com.openingcloud.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.openingcloud.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
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
import com.openingcloud.ai.ragent.rag.dto.AgentStepPayload;
import com.openingcloud.ai.ragent.rag.dto.IntentGroup;
import com.openingcloud.ai.ragent.rag.dto.ReferenceItem;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.exception.TaskCancelledException;
import com.openingcloud.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.openingcloud.ai.ragent.rag.enums.SSEEventType;
import com.openingcloud.ai.ragent.rag.service.ConversationService;
import com.openingcloud.ai.ragent.rag.service.RAGChatService;
import com.openingcloud.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.openingcloud.ai.ragent.rag.service.handler.StreamTaskManager;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.util.NoteWriteIntentHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

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

    private static final String WEB_NEWS_MCP_TOOL_ID = "web_news_search";
    private static final String WEB_SEARCH_MCP_TOOL_ID = "web_search";
    private static final String WEB_REALTIME_MCP_TOOL_ID = "web_realtime_search";
    private static final int STEP_ANALYZE = 1;
    private static final int STEP_INTENT = 2;
    private static final int STEP_RETRIEVE = 3;
    private static final int STEP_SOURCE = 4;
    private static final int STEP_ANSWER = 5;
    private static final Pattern DATE_TIME_QUICK_REPLY_HINT = Pattern.compile(
            "(今天几号|今天几月几号|今天几月几日|今天星期几|今天周几|现在几点|当前时间|当前日期|现在日期|几号|几月几号|几月几日|星期几|周几|日期|时间|date|time|day)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DATE_LOOKUP_HINT = Pattern.compile(
            "(今天几号|今天几月几号|今天几月几日|今天星期几|今天周几|几号|几月几号|几月几日|星期几|周几|日期|date|day)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_LOOKUP_HINT = Pattern.compile(
            "(现在几点|当前时间|时间|time|clock)",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter DATE_CN_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Set<String> CHITCHAT_KEYWORDS = Set.of(
            "你好", "您好", "谢谢", "感谢", "再见", "拜拜",
            "哈哈", "嗯嗯", "好的", "收到", "明白了", "ok"
    );

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final ConversationMemoryPlanner memoryPlanner;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final ConversationService conversationService;
    private final AgentCommandRouter agentCommandRouter;
    private final AgentModeDecider agentModeDecider;
    private final AgentOrchestrator agentOrchestrator;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

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

            ConversationMemorySnapshot memorySnapshot = memoryService.loadSnapshot(actualConversationId, userId);
            memoryService.append(actualConversationId, userId, ChatMessage.user(question));
            ConversationMemoryPlan memoryPlan = memoryPlanner.plan(memorySnapshot, question);

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

            if (isDateTimeLookupQuestion(question)) {
                callback.onContent(buildDateTimeReply(question));
                callback.onComplete();
                return;
            }

            if (isQuickChitchat(question)) {
                StreamCancellationHandle handle = streamSystemResponse(question, callback);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            AtomicReference<String> answerCompletionSummary = new AtomicReference<>("回答生成完成。");
            StreamCallback stageCallback = wrapAnswerStage(emitter, callback, answerCompletionSummary);
            emitProgressStep(emitter, STEP_ANALYZE, "分析问题", "RUNNING", "已接收问题，开始分析。");
            token.throwIfCancelled();
            RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, memoryPlan.getRewriteHistory());
            int subQuestionCount = CollUtil.isEmpty(rewriteResult.subQuestions()) ? 1 : rewriteResult.subQuestions().size();
            emitProgressStep(
                    emitter,
                    STEP_ANALYZE,
                    "分析问题",
                    "SUCCESS",
                    "已完成问题改写，拆解为 " + subQuestionCount + " 个子问题。"
            );

            emitProgressStep(emitter, STEP_INTENT, "识别意图", "RUNNING", "正在识别知识库和联网意图。");
            token.throwIfCancelled();
            List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult, token);
            emitProgressStep(emitter, STEP_INTENT, "识别意图", "SUCCESS", buildIntentSummary(subIntents));

            token.throwIfCancelled();
            GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
            if (guidanceDecision.isPrompt()) {
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在生成澄清提示。");
                stageCallback.onContent(guidanceDecision.getPrompt());
                stageCallback.onComplete();
                return;
            }

            boolean allSystemOnly = subIntents.stream()
                    .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
            if (allSystemOnly) {
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "未命中检索场景，切换通用回答。");
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), stageCallback);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            boolean allEmpty = subIntents.stream()
                    .allMatch(si -> CollUtil.isEmpty(si.nodeScores()));
            if (allEmpty) {
                log.info("所有子问题意图为空，降级为通用 LLM 回答。conversationId={}", actualConversationId);
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "未识别到有效检索意图，切换通用回答。");
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), stageCallback);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            if (Boolean.TRUE.equals(ragConfigProperties.getIntentLowConfidenceFallbackEnabled())) {
                if (isLowConfidenceWithNoMcp(subIntents)) {
                    log.info("所有子问题意图置信度过低且无 MCP 匹配，降级为通用 LLM 回答。conversationId={}", actualConversationId);
                    emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "检索置信度不足，切换通用回答。");
                    StreamCancellationHandle handle = streamSystemResponse(
                            rewriteResult.rewrittenQuestion(), stageCallback);
                    taskManager.bindHandle(taskId, handle);
                    return;
                }
            }

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
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), stageCallback);
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
            if (shouldDirectReplyWithMcpOnlyTool(subIntents, ctx, WEB_NEWS_MCP_TOOL_ID)
                    || shouldDirectReplyWithMcpOnlyTool(subIntents, ctx, WEB_REALTIME_MCP_TOOL_ID)) {
                emitProgressStep(emitter, STEP_SOURCE, "整理来源", "SUCCESS", buildSourceSummary(ctx));
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "正在整理联网结果。");
                stageCallback.onContent(extractDirectMcpReply(ctx.getMcpContext()));
                stageCallback.onComplete();
                return;
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
            if (ctx.isEmpty()) {
                if (shouldFallbackToGeneralModelOnWebSearchMiss(subIntents)) {
                    emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "联网未命中结果，回退到通用回答。");
                    stageCallback.onContent("联网检索暂未命中可靠结果，以下回答基于通用模型能力生成。\n\n");
                    StreamCancellationHandle handle = streamSystemResponse(
                            buildDirectAnswerFallbackQuestion(rewriteResult.rewrittenQuestion()),
                            stageCallback
                    );
                    taskManager.bindHandle(taskId, handle);
                    return;
                }
                String emptyReply = "未检索到与问题相关的文档内容。";
                emitAnswerProgressStep(emitter, answerCompletionSummary, "RUNNING", "未命中相关上下文，返回兜底说明。");
                stageCallback.onContent(emptyReply);
                stageCallback.onComplete();
                return;
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
            StreamCancellationHandle handle = streamLLMResponse(
                    rewriteResult,
                    ctx,
                    mergedGroup,
                    memoryPlan.getAnswerHistory(),
                    thinkingEnabled,
                    stageCallback
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

    private boolean isDateTimeLookupQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = StrUtil.trim(question);
        boolean isDateTimeLookup = DATE_TIME_QUICK_REPLY_HINT.matcher(normalized).find();
        if (!isDateTimeLookup) {
            return false;
        }
        return !NoteWriteIntentHelper.isLikelyNoteWriteQuestion(normalized);
    }

    private boolean isQuickChitchat(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String q = question.trim();
        return q.length() <= 6 && CHITCHAT_KEYWORDS.contains(q);
    }

    private String buildDateTimeReply(String question) {
        String normalized = StrUtil.blankToDefault(question, "").trim();
        ZonedDateTime now = ZonedDateTime.now();
        String dateText = DATE_CN_FORMATTER.format(now);
        String timeText = TIME_FORMATTER.format(now);
        String weekdayText = toChineseWeekday(now.getDayOfWeek());
        String zoneId = now.getZone().getId();
        boolean asksDate = DATE_LOOKUP_HINT.matcher(normalized).find();
        boolean asksTime = TIME_LOOKUP_HINT.matcher(normalized).find();

        if (asksTime && !asksDate) {
            return "现在时间是 " + timeText + "（" + zoneId + "，" + weekdayText + "）。";
        }
        if (asksDate && !asksTime) {
            return "今天是 " + dateText + "，" + weekdayText + "。";
        }
        return "现在是 " + dateText + " " + timeText + "（" + zoneId + "，" + weekdayText + "）。";
    }

    private String toChineseWeekday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private boolean shouldDirectReplyWithMcpOnlyTool(List<SubQuestionIntent> subIntents,
                                                     RetrievalContext ctx,
                                                     String targetToolId) {
        if (ctx == null || StrUtil.isBlank(ctx.getMcpContext()) || CollUtil.isEmpty(subIntents)) {
            return false;
        }
        boolean hasTargetToolIntent = false;
        for (SubQuestionIntent subIntent : subIntents) {
            if (subIntent == null || CollUtil.isEmpty(subIntent.nodeScores())) {
                continue;
            }
            for (var nodeScore : subIntent.nodeScores()) {
                if (nodeScore == null || nodeScore.getNode() == null) {
                    continue;
                }
                if (nodeScore.getNode().getKind() != IntentKind.MCP) {
                    continue;
                }
                String toolId = nodeScore.getNode().getMcpToolId();
                if (targetToolId.equals(toolId)) {
                    hasTargetToolIntent = true;
                    continue;
                }
                if (StrUtil.isNotBlank(toolId)) {
                    return false;
                }
            }
        }
        return hasTargetToolIntent;
    }

    private String extractDirectMcpReply(String mcpContext) {
        String normalized = StrUtil.blankToDefault(mcpContext, "").trim();
        if (StrUtil.isBlank(normalized)) {
            return "联网检索失败，请稍后重试。";
        }

        String marker = "#### 动态数据片段";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex >= 0) {
            normalized = normalized.substring(markerIndex + marker.length()).trim();
        }
        List<String> cleanedLines = new ArrayList<>();
        for (String line : normalized.split("\\R")) {
            String trimmed = line.trim();
            if (StrUtil.isBlank(trimmed)
                    || "---".equals(trimmed)
                    || trimmed.startsWith("**子问题**")
                    || "**相关文档**：".equals(trimmed)) {
                continue;
            }
            cleanedLines.add(line);
        }
        return cleanedLines.isEmpty() ? normalized : String.join("\n", cleanedLines).trim();
    }

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
        String systemPrompt = promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(ragConfigProperties.getChatSystemTemperature())
                .topP(ragConfigProperties.getChatSystemTopP())
                .maxTokens(ragConfigProperties.getChatMaxTokensSystem())
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private String buildDirectAnswerFallbackQuestion(String question) {
        String normalized = StrUtil.blankToDefault(question, "").trim();
        return """
                请直接回答下面的问题，优先用 1 到 2 句给出定义或核心介绍。
                不要自我介绍，不要重复说明自己是助手，不要补充无关寒暄，也不要追加“如果你还想了解更多可以继续问我”这类收尾。
                只有当问题本身明确要求最新、当前、实时信息时，才简短提示信息可能存在时效性；否则不要主动添加时效免责声明，也不要编造来源。
                问题：%s
                """.formatted(normalized);
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

    /**
     * 判断所有子问题是否都满足：无 MCP 意图 且 KB 意图最高分 < 低置信度阈值。
     * 用于在检索前拦截不相关查询，降级为通用 LLM 回答。
     */
    private boolean isLowConfidenceWithNoMcp(List<SubQuestionIntent> subIntents) {
        double threshold = ragConfigProperties.getIntentLowConfidenceThreshold();
        for (SubQuestionIntent si : subIntents) {
            if (CollUtil.isEmpty(si.nodeScores())) {
                continue;
            }
            boolean hasMcp = si.nodeScores().stream()
                    .anyMatch(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP);
            if (hasMcp) {
                return false;
            }
            double maxKbScore = si.nodeScores().stream()
                    .filter(ns -> ns.getNode() != null
                            && (ns.getNode().getKind() == null || ns.getNode().getKind() == IntentKind.KB))
                    .mapToDouble(NodeScore::getScore)
                    .max()
                    .orElse(0.0);
            if (maxKbScore >= threshold) {
                return false;
            }
        }
        return true;
    }

    private StreamCallback wrapAnswerStage(SseEmitter emitter,
                                           StreamCallback delegate,
                                           AtomicReference<String> completionSummaryRef) {
        AtomicBoolean finished = new AtomicBoolean(false);
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
            public void onComplete() {
                if (finished.compareAndSet(false, true)) {
                    emitProgressStep(
                            emitter,
                            STEP_ANSWER,
                            "生成回答",
                            "SUCCESS",
                            StrUtil.blankToDefault(completionSummaryRef.get(), "回答生成完成。")
                    );
                }
                delegate.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                if (finished.compareAndSet(false, true)) {
                    emitProgressStep(emitter, STEP_ANSWER, "生成回答", "FAILED", buildStageErrorSummary(error));
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

    private boolean shouldFallbackToGeneralModelOnWebSearchMiss(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return false;
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
                if (StrUtil.isBlank(toolId)) {
                    continue;
                }
                if (!WEB_SEARCH_MCP_TOOL_ID.equals(toolId)) {
                    return false;
                }
                hasWebSearch = true;
            }
        }
        return hasWebSearch;
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
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(SSEEventType.AGENT_STEP.value())
                    .data(new AgentStepPayload(0, stepIndex, type, status, summary, null, null)));
        } catch (IOException e) {
            log.warn("发送普通聊天阶段事件失败, stepIndex={}, type={}", stepIndex, type, e);
        }
    }
}
