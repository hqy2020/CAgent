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
import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.agent.AgentCommandRouter;
import com.nageoffer.ai.ragent.rag.agent.AgentModeDecider;
import com.nageoffer.ai.ragent.rag.agent.AgentModeDecision;
import com.nageoffer.ai.ragent.rag.agent.AgentOrchestrator;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
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
import com.nageoffer.ai.ragent.rag.dto.ReferenceItem;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;
import com.nageoffer.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
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
import java.util.stream.Collectors;

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

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
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

            List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));

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
            AgentModeDecision agentModeDecision = agentModeDecider.decide(
                    rewriteResult.rewrittenQuestion(),
                    subIntents,
                    ctx
            );
            if (agentModeDecision.enabled()) {
                log.info("进入 Agentic RAG 模式，reason={}, confidence={}, conversationId={}, taskId={}",
                        agentModeDecision.reason(), agentModeDecision.confidence(), actualConversationId, taskId);
                boolean handled = agentOrchestrator.execute(AgentOrchestrator.AgentExecuteRequest.builder()
                        .question(rewriteResult.rewrittenQuestion())
                        .conversationId(actualConversationId)
                        .userId(userId)
                        .history(history)
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
                String emptyReply = "未检索到与问题相关的文档内容。";
                callback.onContent(emptyReply);
                callback.onComplete();
                return;
            }

            // 发送参考文档引用事件（在 LLM 流式输出前）
            sendReferencesEvent(emitter, ctx);

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
                .temperature(ragConfigProperties.getChatSystemTemperature())
                .topP(ragConfigProperties.getChatSystemTopP())
                .maxTokens(ragConfigProperties.getChatMaxTokensSystem())
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
}
