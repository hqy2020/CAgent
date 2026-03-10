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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.trace.RagTraceNode;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.infra.util.LLMResponseCleaner;
import com.openingcloud.ai.ragent.knowledge.graph.GraphEntityExtractor;
import com.openingcloud.ai.ragent.knowledge.graph.GraphRepository;
import com.openingcloud.ai.ragent.knowledge.graph.GraphTriple;
import com.openingcloud.ai.ragent.rag.config.KnowledgeGraphProperties;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.graph.GraphTripleFormatter;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequestSource;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.openingcloud.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.openingcloud.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.openingcloud.ai.ragent.rag.core.rewrite.RewriteResult;
import com.openingcloud.ai.ragent.rag.exception.TaskCancelledException;
import com.openingcloud.ai.ragent.rag.dto.AgentConfirmPayload;
import com.openingcloud.ai.ragent.rag.dto.AgentObservePayload;
import com.openingcloud.ai.ragent.rag.dto.AgentPlanPayload;
import com.openingcloud.ai.ragent.rag.dto.AgentReplanPayload;
import com.openingcloud.ai.ragent.rag.dto.AgentStepPayload;
import com.openingcloud.ai.ragent.rag.dto.ReferenceItem;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.rag.enums.SSEEventType;
import com.openingcloud.ai.ragent.rag.util.NoteWriteIntentHelper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * Agent 编排器（ReAct: Observe -> Reason -> Act）
 */
@Slf4j
@Component
public class AgentOrchestrator {

    private static final double MCP_EXECUTION_MIN_INTENT_SCORE = 0.60D;
    private static final Pattern DATE_TIME_LOOKUP_HINT = Pattern.compile(
            "(今天几号|今天星期几|今天周几|现在几点|当前时间|当前日期|几号|星期几|周几|日期|时间|date|time|day)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NOTE_TITLE_PATTERN = Pattern.compile("《([^》]+)》");

    private static final int MAX_OBSERVATION_ITEMS = 5;
    private static final int MAX_RECENT_ACTIONS = 6;
    private static final int MAX_REASONING_DETAIL_CHARS = 420;

    private static final String REASONER_SYSTEM_PROMPT = """
            你是一个按照 ReAct（Observe -> Reason -> Act）循环工作的任务型 Agent。
            你必须先阅读 observations 和 recentActions，再决定下一步行动。
            请输出严格 JSON：
            {
              "goal":"当前阶段目标",
              "thought":"基于 observation 的判断",
              "done": true/false,
              "finalAnswer":"可选；仅在 done=true 且无需继续 act 时输出",
              "action":{
                "type":"KB_RETRIEVE|GRAPH_QUERY|MCP_CALL|SYNTHESIZE|FINAL_ANSWER",
                "instruction":"下一步动作说明",
                "query":"检索词或工具输入",
                "toolId":"可选工具ID",
                "params":{}
              }
            }
            规则：
            1) 每次只能选择 1 个 action；
            2) thought 必须说明是基于哪些 observation 做出的判断；
            3) 如果 observation 已足够回答用户，done=true，并将 action.type 设为 FINAL_ANSWER；
            4) 如果用户目标包含“创建/更新/写入/添加/加入/插入/补充/替换/删除笔记”，必须优先选择 MCP_CALL，而不是只给建议；
            5) 如果最近动作已经失败，优先换策略，避免重复同一个失败动作；
            6) 如果当前没有证据，优先检索或调用工具，不要空想；
            7) JSON 之外不要输出额外文本。
            """;

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final MCPService mcpService;
    private final MCPToolRegistry mcpToolRegistry;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final PendingProposalStore pendingProposalStore;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private GraphRepository graphRepository;

    @Autowired(required = false)
    private GraphEntityExtractor graphEntityExtractor;

    @Autowired(required = false)
    private GraphTripleFormatter graphTripleFormatter;

    @Autowired(required = false)
    private KnowledgeGraphProperties knowledgeGraphProperties;

    public AgentOrchestrator(LLMService llmService,
                             RAGConfigProperties ragConfigProperties,
                             QueryRewriteService queryRewriteService,
                             IntentResolver intentResolver,
                             RetrievalEngine retrievalEngine,
                             MCPService mcpService,
                             MCPToolRegistry mcpToolRegistry,
                             MCPParameterExtractor mcpParameterExtractor,
                             PendingProposalStore pendingProposalStore,
                             ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.ragConfigProperties = ragConfigProperties;
        this.queryRewriteService = queryRewriteService;
        this.intentResolver = intentResolver;
        this.retrievalEngine = retrievalEngine;
        this.mcpService = mcpService;
        this.mcpToolRegistry = mcpToolRegistry;
        this.mcpParameterExtractor = mcpParameterExtractor;
        this.pendingProposalStore = pendingProposalStore;
        this.objectMapper = objectMapper;
    }

    @RagTraceNode(name = "agent-plan", type = "AGENT_PLAN")
    public boolean execute(AgentExecuteRequest request) {
        try {
            int maxLoops = Math.max(1, Optional.ofNullable(ragConfigProperties.getAgentMaxLoops()).orElse(3));
            int maxStepsPerLoop = Math.max(1, Optional.ofNullable(ragConfigProperties.getAgentMaxStepsPerLoop()).orElse(6));
            int maxActions = maxLoops * maxStepsPerLoop;

            StringBuilder evidence = new StringBuilder();
            List<AgentObservation> observations = new ArrayList<>();
            List<ActionRecord> actionHistory = new ArrayList<>();
            seedInitialObservations(request, evidence, observations);

            for (int actionCursor = 1; actionCursor <= maxActions; actionCursor++) {
                request.token().throwIfCancelled();
                int loop = ((actionCursor - 1) / maxStepsPerLoop) + 1;
                int stepIndex = ((actionCursor - 1) % maxStepsPerLoop) + 1;

                sendObservation(request.emitter(), observe(loop, stepIndex, observations));

                ReasoningDecision decision = reason(
                        request.question(),
                        observations,
                        actionHistory,
                        maxActions - actionCursor + 1
                );
                AgentPlanStep step = normalizeReasoningStep(request.question(), decision, observations, actionHistory);

                if (decision.done() || step == null || "FINAL_ANSWER".equals(normalizeStepType(step.type()))) {
                    String answer = resolveDoneAnswer(request.question(), evidence.toString(), decision);
                    if (StrUtil.isBlank(answer)) {
                        break;
                    }
                    request.callback().onContent(answer);
                    request.callback().onComplete();
                    return true;
                }

                sendAgentPlan(request.emitter(), loop, stepIndex, new AgentPlan(
                        StrUtil.blankToDefault(decision.goal(), request.question()),
                        List.of(step)
                ));

                StepExecutionResult result = executeWithRetry(step, request, evidence, loop, stepIndex);
                sendAgentStep(request.emitter(), loop, stepIndex, step, result);

                AgentObservation newObservation = buildStepObservation(loop, stepIndex, step, result);
                observations.add(newObservation);
                actionHistory.add(new ActionRecord(loop, stepIndex, step, result.success(), result.summary(), result.error()));

                if (result.confirmRequired()) {
                    PendingProposal proposal = result.proposal();
                    sendConfirmRequired(request.emitter(), proposal);
                    request.callback().onContent("检测到写操作，需要你确认后才会执行。\n"
                            + "请输入 `/confirm " + proposal.getProposalId() + "` 执行，或 `/reject " + proposal.getProposalId() + "` 取消。");
                    request.callback().onComplete();
                    return true;
                }

                if (shouldEmitReplan(result, stepIndex, maxStepsPerLoop, actionCursor < maxActions)) {
                    sendReplan(request.emitter(), loop, new ReflectionResult(
                            false,
                            buildReplanReason(step, result),
                            List.of(step)
                    ));
                }
            }

            if (StrUtil.isNotBlank(evidence.toString())) {
                String answer = synthesizeFinalAnswer(
                        request.question(),
                        evidence.toString(),
                        "已达到最大循环次数，以下答案基于当前可用信息，并保留未完成风险。"
                );
                request.callback().onContent(answer);
                request.callback().onComplete();
                return true;
            }

            return false;
        } catch (TaskCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent 编排执行失败", e);
            return false;
        }
    }

    private StepExecutionResult executeWithRetry(AgentPlanStep step,
                                                 AgentExecuteRequest request,
                                                 StringBuilder evidence,
                                                 int loop,
                                                 int stepIndex) {
        StepExecutionResult result = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                result = executeStep(step, request, evidence, loop, stepIndex);
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("Agent 步骤执行失败，准备重试。loop={}, step={}, attempt={}", loop, stepIndex, attempt, e);
            }
        }
        if (result == null && lastError != null) {
            return StepExecutionResult.failed("步骤执行失败：" + lastError.getMessage(), lastError.getMessage());
        }
        return result == null
                ? StepExecutionResult.failed("步骤执行失败：未知错误", "unknown")
                : result;
    }

    private void seedInitialObservations(AgentExecuteRequest request,
                                         StringBuilder evidence,
                                         List<AgentObservation> observations) {
        RetrievalContext firstRoundContext = request.firstRoundContext();
        if (firstRoundContext == null || firstRoundContext.isEmpty()) {
            observations.add(new AgentObservation(
                    1,
                    0,
                    "initial",
                    "EMPTY",
                    "首轮观察暂无证据，下一步应优先执行检索或工具调用。",
                    ""
            ));
            return;
        }
        appendEvidence(evidence, "first-round", firstRoundContext.getKbContext(), firstRoundContext.getMcpContext());
        if (firstRoundContext.hasKb()) {
            observations.add(new AgentObservation(
                    1,
                    0,
                    "first-round-kb",
                    "SUCCESS",
                    "首轮观察已命中知识库证据。",
                    firstRoundContext.getKbContext()
            ));
        }
        if (firstRoundContext.hasMcp()) {
            observations.add(new AgentObservation(
                    1,
                    0,
                    "first-round-mcp",
                    "SUCCESS",
                    "首轮观察已获得工具上下文。",
                    firstRoundContext.getMcpContext()
            ));
        }
    }

    private AgentObservePayload observe(int loop, int stepIndex, List<AgentObservation> observations) {
        List<AgentObservation> recent = observations.stream()
                .sorted(Comparator.comparingInt(AgentObservation::loop)
                        .thenComparingInt(AgentObservation::stepIndex))
                .skip(Math.max(0, observations.size() - MAX_OBSERVATION_ITEMS))
                .toList();
        List<AgentObservePayload.ObservationItem> items = recent.stream()
                .map(each -> new AgentObservePayload.ObservationItem(each.source(), each.status(), each.summary()))
                .toList();
        String summary;
        if (items.isEmpty()) {
            summary = "当前暂无 observation，优先补证据。";
        } else {
            summary = recent.get(recent.size() - 1).summary();
        }
        return new AgentObservePayload(loop, stepIndex, summary, items);
    }

    @RagTraceNode(name = "agent-reason", type = "AGENT_PLAN")
    protected ReasoningDecision reason(String question,
                                       List<AgentObservation> observations,
                                       List<ActionRecord> actionHistory,
                                       int remainingSteps) {
        String userPrompt = """
                用户问题：
                %s

                observations：
                %s

                recentActions：
                %s

                剩余行动预算：%s
                """.formatted(
                question,
                renderObservations(observations),
                renderActionHistory(actionHistory),
                remainingSteps
        );

        String raw = llmService.chat(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(REASONER_SYSTEM_PROMPT),
                        ChatMessage.user(userPrompt)
                ))
                .thinking(true)
                .temperature(0.1D)
                .topP(0.8D)
                .maxTokens(1024)
                .build());
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            String goal = root.path("goal").asText(question);
            String thought = root.path("thought").asText("基于当前 observation 决定下一步动作。");
            boolean done = root.path("done").asBoolean(false);
            String finalAnswer = root.path("finalAnswer").asText(null);
            JsonNode actionNode = root.path("action");
            AgentPlanStep action = actionNode.isMissingNode() || actionNode.isNull()
                    ? null
                    : parseAction(actionNode, question);
            return new ReasoningDecision(goal, thought, done, finalAnswer, action);
        } catch (Exception e) {
            return fallbackReasoning(question, observations, actionHistory);
        }
    }

    private ReasoningDecision fallbackReasoning(String question,
                                                List<AgentObservation> observations,
                                                List<ActionRecord> actionHistory) {
        boolean hasEvidence = observations.stream()
                .anyMatch(each -> "SUCCESS".equals(each.status()) && StrUtil.isNotBlank(each.detail()));
        boolean hasWriteAction = actionHistory.stream()
                .map(ActionRecord::step)
                .filter(step -> step != null && StrUtil.isNotBlank(step.toolId()))
                .map(AgentPlanStep::toolId)
                .anyMatch(this::isWriteTool);
        if (isLikelyNoteWriteQuestion(question) && !hasWriteAction) {
            return new ReasoningDecision(
                    question,
                    "问题包含明确写入意图，优先准备写工具调用。",
                    false,
                    null,
                    new AgentPlanStep("MCP_CALL", "准备 Obsidian 写操作并等待用户确认", question, suggestWriteToolId(question), Map.of())
            );
        }
        if (hasEvidence && !actionHistory.isEmpty()
                && "SYNTHESIZE".equals(normalizeStepType(actionHistory.get(actionHistory.size() - 1).step().type()))
                && actionHistory.get(actionHistory.size() - 1).success()) {
            return new ReasoningDecision(question, "汇总动作已完成，可直接输出最终回答。", true, null, null);
        }
        if (hasEvidence) {
            return new ReasoningDecision(
                    question,
                    "已有 observation 可支撑回答，降级直接汇总。",
                    false,
                    null,
                    new AgentPlanStep("SYNTHESIZE", "汇总当前 observation 并生成回答草稿", question, null, Map.of())
            );
        }
        if (!actionHistory.isEmpty() && !actionHistory.get(actionHistory.size() - 1).success()) {
            return new ReasoningDecision(question, "最近动作失败，先停止循环并回退。", true, null, null);
        }
        return new ReasoningDecision(
                question,
                "暂无足够 observation，默认补一次知识库检索。",
                false,
                null,
                new AgentPlanStep("KB_RETRIEVE", "补充知识库检索", question, null, Map.of())
        );
    }

    private AgentPlanStep parseAction(JsonNode node, String fallbackQuery) {
        String type = normalizeStepType(node.path("type").asText("KB_RETRIEVE"));
        String instruction = node.path("instruction").asText("执行步骤");
        String query = node.path("query").asText(fallbackQuery);
        String toolId = node.path("toolId").asText(null);
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode paramsNode = node.path("params");
        if (paramsNode.isObject()) {
            paramsNode.fields().forEachRemaining(entry -> params.put(entry.getKey(), parseJsonNode(entry.getValue())));
        }
        return new AgentPlanStep(type, instruction, query, toolId, params);
    }

    private AgentPlanStep normalizeReasoningStep(String question,
                                                 ReasoningDecision decision,
                                                 List<AgentObservation> observations,
                                                 List<ActionRecord> actionHistory) {
        AgentPlanStep action = decision.action();
        if (action == null) {
            return fallbackReasoning(question, observations, actionHistory).action();
        }
        String normalizedType = normalizeStepType(action.type());
        if (shouldForceWriteAction(question, normalizedType, actionHistory)) {
            return new AgentPlanStep(
                    "MCP_CALL",
                    "已有汇总结果，进入写操作确认",
                    question,
                    resolveCompatibleToolId(question, null, actionHistory),
                    Map.of()
            );
        }
        if (shouldForceFinalAnswer(question, normalizedType, actionHistory)) {
            return new AgentPlanStep(
                    "FINAL_ANSWER",
                    "已有汇总结果，直接输出最终回答",
                    question,
                    null,
                    Map.of()
            );
        }
        if ("FINAL_ANSWER".equals(normalizedType)) {
            return action;
        }
        if ("MCP_CALL".equals(normalizedType)) {
            return new AgentPlanStep(
                    action.type(),
                    action.instruction(),
                    action.query(),
                    resolveCompatibleToolId(question, action.toolId(), actionHistory),
                    action.params()
            );
        }
        return new AgentPlanStep(normalizedType, action.instruction(), action.query(), action.toolId(), action.params());
    }

    private boolean shouldForceWriteAction(String question,
                                           String normalizedType,
                                           List<ActionRecord> actionHistory) {
        if (!isLikelyNoteWriteQuestion(question) || CollUtil.isEmpty(actionHistory) || "MCP_CALL".equals(normalizedType)) {
            return false;
        }
        ActionRecord latest = actionHistory.get(actionHistory.size() - 1);
        return latest.success()
                && latest.step() != null
                && "SYNTHESIZE".equals(normalizeStepType(latest.step().type()));
    }

    private boolean shouldForceFinalAnswer(String question,
                                           String normalizedType,
                                           List<ActionRecord> actionHistory) {
        if (CollUtil.isEmpty(actionHistory) || isLikelyNoteWriteQuestion(question)) {
            return false;
        }
        ActionRecord latest = actionHistory.get(actionHistory.size() - 1);
        if (!latest.success() || latest.step() == null) {
            return false;
        }
        if (!"SYNTHESIZE".equals(normalizeStepType(latest.step().type()))
                || "FINAL_ANSWER".equals(normalizedType)) {
            return false;
        }
        return true;
    }

    private String resolveCompatibleToolId(String question,
                                           String candidateToolId,
                                           List<ActionRecord> actionHistory) {
        String normalizedToolId = StrUtil.trim(candidateToolId);
        if (StrUtil.isNotBlank(normalizedToolId)
                && mcpToolRegistry.getExecutor(normalizedToolId).isPresent()) {
            return normalizedToolId;
        }
        if (isLikelyNoteWriteQuestion(question)) {
            return suggestWriteToolId(question);
        }
        String latestToolId = resolveLatestToolId(actionHistory);
        if (StrUtil.isNotBlank(latestToolId) && mcpToolRegistry.getExecutor(latestToolId).isPresent()) {
            return latestToolId;
        }
        return normalizedToolId;
    }

    private String resolveDoneAnswer(String question, String evidence, ReasoningDecision decision) {
        if (StrUtil.isNotBlank(evidence)) {
            return synthesizeFinalAnswer(
                    question,
                    evidence,
                    StrUtil.blankToDefault(decision.thought(), "基于 observation 判断已可完成回答。")
            );
        }
        if (StrUtil.isNotBlank(decision.finalAnswer())) {
            return decision.finalAnswer();
        }
        return null;
    }

    private boolean shouldEmitReplan(StepExecutionResult result,
                                     int stepIndex,
                                     int maxStepsPerLoop,
                                     boolean hasMoreBudget) {
        if (!hasMoreBudget) {
            return false;
        }
        return !result.success() || stepIndex >= maxStepsPerLoop;
    }

    private String buildReplanReason(AgentPlanStep step, StepExecutionResult result) {
        if (result.success()) {
            return "已完成动作 " + normalizeStepType(step.type()) + "，继续依据新的 observation 判断下一步。";
        }
        return "动作 " + normalizeStepType(step.type()) + " 执行未达预期，需要根据失败 observation 调整策略。";
    }

    private String renderObservations(List<AgentObservation> observations) {
        if (CollUtil.isEmpty(observations)) {
            return "无 observation";
        }
        return observations.stream()
                .sorted(Comparator.comparingInt(AgentObservation::loop)
                        .thenComparingInt(AgentObservation::stepIndex))
                .skip(Math.max(0, observations.size() - MAX_OBSERVATION_ITEMS))
                .map(each -> "[%s][%s] %s | detail=%s".formatted(
                        each.source(),
                        each.status(),
                        each.summary(),
                        clip(each.detail(), MAX_REASONING_DETAIL_CHARS)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String renderActionHistory(List<ActionRecord> actionHistory) {
        if (CollUtil.isEmpty(actionHistory)) {
            return "无 recentActions";
        }
        return actionHistory.stream()
                .skip(Math.max(0, actionHistory.size() - MAX_RECENT_ACTIONS))
                .map(each -> "loop=%s step=%s type=%s status=%s summary=%s".formatted(
                        each.loop(),
                        each.stepIndex(),
                        normalizeStepType(each.step().type()),
                        each.success() ? "SUCCESS" : "FAILED",
                        clip(StrUtil.blankToDefault(each.summary(), each.error()), 160)
                ))
                .collect(Collectors.joining("\n"));
    }

    private AgentObservation buildStepObservation(int loop,
                                                 int stepIndex,
                                                 AgentPlanStep step,
                                                 StepExecutionResult result) {
        String status;
        if (result.confirmRequired()) {
            status = "CONFIRM_REQUIRED";
        } else if (result.success()) {
            status = "SUCCESS";
        } else {
            status = "FAILED";
        }
        String detail = result.confirmRequired()
                ? StrUtil.blankToDefault(result.proposalSummary(), result.summary())
                : StrUtil.blankToDefault(result.observationDetail(), result.error());
        return new AgentObservation(loop, stepIndex, normalizeStepType(step.type()), status, result.summary(), detail);
    }

    @RagTraceNode(name = "agent-step", type = "AGENT_STEP")
    protected StepExecutionResult executeStep(AgentPlanStep step,
                                              AgentExecuteRequest request,
                                              StringBuilder evidence,
                                              int loop,
                                              int stepIndex) {
        String type = normalizeStepType(step.type());
        return switch (type) {
            case "KB_RETRIEVE" -> executeKbRetrieve(step, request, evidence);
            case "GRAPH_QUERY" -> executeGraphQuery(step, request, evidence);
            case "MCP_CALL" -> executeMcpCall(step, request, evidence);
            case "SYNTHESIZE" -> executeSynthesize(step, request, evidence);
            default -> StepExecutionResult.failed("不支持的步骤类型：" + type, "unsupported-step-type");
        };
    }

    private StepExecutionResult executeKbRetrieve(AgentPlanStep step,
                                                  AgentExecuteRequest request,
                                                  StringBuilder evidence) {
        String query = StrUtil.blankToDefault(step.query(), request.question());
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(
                query,
                request.history() == null ? List.of() : request.history()
        );
        List<SubQuestionIntent> intents = intentResolver.resolve(rewriteResult, request.token());
        List<SubQuestionIntent> kbIntents = intents.stream()
                .map(each -> new SubQuestionIntent(
                        each.subQuestion(),
                        each.nodeScores() == null ? List.of() : each.nodeScores().stream()
                                .filter(ns -> ns != null && ns.getNode() != null)
                                .filter(ns -> ns.getNode().getKind() == null || ns.getNode().getKind() == IntentKind.KB)
                                .toList()
                ))
                .filter(each -> CollUtil.isNotEmpty(each.nodeScores()))
                .toList();
        List<SubQuestionIntent> retrievalTargets = kbIntents;
        String successSummary = "KB 检索完成，已获得相关证据";
        if (kbIntents.isEmpty()) {
            log.info("未识别到可用 KB 意图，KB_RETRIEVE 降级为全局知识检索, query={}", query);
            retrievalTargets = List.of(new SubQuestionIntent(query, List.of()));
            successSummary = "未识别到明确 KB 意图，已通过全局知识检索获得相关证据";
        }
        RetrievalContext context = retrievalEngine.retrieve(retrievalTargets, DEFAULT_TOP_K, request.token());
        if (context == null || StrUtil.isBlank(context.getKbContext())) {
            return StepExecutionResult.failed("KB 检索无结果", "kb-empty");
        }
        appendEvidence(evidence, "kb-step", context.getKbContext(), null);
        List<ReferenceItem> references = buildReferences(context);
        return StepExecutionResult.success(successSummary, references, context.getKbContext());
    }

    private StepExecutionResult executeGraphQuery(AgentPlanStep step,
                                                   AgentExecuteRequest request,
                                                   StringBuilder evidence) {
        // 图谱未启用时降级为 KB 检索
        if (graphRepository == null || graphEntityExtractor == null || graphTripleFormatter == null) {
            log.info("图谱未启用，GRAPH_QUERY 降级为 KB_RETRIEVE");
            return executeKbRetrieve(step, request, evidence);
        }

        String query = StrUtil.blankToDefault(step.query(), request.question());

        // 1. 实体识别
        List<String> entities = graphEntityExtractor.extractEntities(query);
        if (entities.isEmpty()) {
            return StepExecutionResult.failed("未从查询中识别出实体", "graph-no-entities");
        }

        // 2. 获取所有知识库 collection 进行图遍历
        int maxHops = knowledgeGraphProperties != null ? knowledgeGraphProperties.getTraversalMaxHops() : 2;
        int maxNodes = knowledgeGraphProperties != null ? knowledgeGraphProperties.getTraversalMaxNodes() : 20;

        List<GraphTriple> allTriples = new ArrayList<>();
        // 使用意图中的 kbId 或全量遍历
        List<String> kbIds = resolveKbIdsFromIntents(request);
        if (kbIds.isEmpty()) {
            // 降级：尝试全量（此处简化，取第一跳即可）
            kbIds = List.of("_all");
        }
        for (String kbId : kbIds) {
            List<GraphTriple> triples = graphRepository.traverseByEntities(kbId, entities, maxHops, maxNodes);
            allTriples.addAll(triples);
        }

        if (allTriples.isEmpty()) {
            return StepExecutionResult.failed("图谱查询无结果", "graph-empty");
        }

        // 3. 格式化证据
        List<RetrievedChunk> chunks = graphTripleFormatter.toRetrievedChunks(allTriples);
        String graphEvidence = chunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        appendEvidence(evidence, "graph-step", graphEvidence, null);
        return StepExecutionResult.success("图谱查询完成，发现 " + allTriples.size() + " 条关系", List.of(), graphEvidence);
    }

    private List<String> resolveKbIdsFromIntents(AgentExecuteRequest request) {
        if (request.subIntents() == null) {
            return List.of();
        }
        List<String> kbIds = new ArrayList<>();
        for (SubQuestionIntent sub : request.subIntents()) {
            if (sub.nodeScores() == null) {
                continue;
            }
            for (NodeScore ns : sub.nodeScores()) {
                if (ns != null && ns.getNode() != null && ns.getNode().getCollectionName() != null) {
                    kbIds.add(ns.getNode().getCollectionName());
                }
            }
        }
        return kbIds.stream().distinct().toList();
    }

    private StepExecutionResult executeMcpCall(AgentPlanStep step,
                                               AgentExecuteRequest request,
                                               StringBuilder evidence) {
        String query = StrUtil.blankToDefault(step.query(), request.question());
        String toolId = resolveToolId(step, request);
        if (StrUtil.isBlank(toolId)) {
            return StepExecutionResult.failed("MCP 步骤缺少 toolId，且无法从意图推断", "tool-id-missing");
        }

        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            return StepExecutionResult.failed("MCP 工具不存在：" + toolId, "tool-not-found");
        }
        MCPTool tool = executorOpt.get().getToolDefinition();
        boolean writeTool = isWriteTool(toolId, tool);
        if (!isMcpExecutionAllowed(toolId, request)) {
            return StepExecutionResult.failed("MCP 执行已跳过：缺少高置信度意图支持", "mcp-confidence-low");
        }
        if (writeTool && isDateTimeLookupQuestion(query)) {
            return StepExecutionResult.failed("检测到时间查询，已阻止写入工具调用", "write-tool-blocked-datetime-query");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (step.params() != null && !step.params().isEmpty()) {
            params.putAll(step.params());
        } else {
            params.putAll(mcpParameterExtractor.extractParameters(query, tool, null));
        }
        if (writeTool) {
            enrichWriteParams(toolId, request.question(), evidence.toString(), params);
        }
        MCPParameterExtractor.ParameterValidationResult validationResult = mcpParameterExtractor.validate(params, tool);
        if (!validationResult.valid()) {
            String missing = String.join("、", validationResult.missingParams());
            String detail = StrUtil.blankToDefault(validationResult.clarificationMessage(), "请补充必要参数后重试。");
            return StepExecutionResult.failed("MCP 调用缺少必要参数：" + missing + "。 " + detail, "mcp-missing-required-params");
        }
        params = validationResult.params();

        MCPRequest mcpRequest = MCPRequest.builder()
                .toolId(toolId)
                .userId(request.userId())
                .conversationId(request.conversationId())
                .userQuestion(query)
                .requestSource(MCPRequestSource.AGENT_STEP)
                .parameters(params)
                .build();

        if (writeTool) {
            PendingProposal proposal = pendingProposalStore.create(
                    request.userId(),
                    request.conversationId(),
                    mcpRequest,
                    resolveTargetPath(params),
                    "写操作默认需要人工确认，防止误写入"
            );
            return StepExecutionResult.confirmRequired(
                    "检测到写操作，已创建待确认提案",
                    proposal,
                    "待确认写操作：toolId=%s, targetPath=%s".formatted(
                            proposal.getToolId(),
                            StrUtil.blankToDefault(proposal.getTargetPath(), "未解析目标路径")
                    )
            );
        }

        MCPResponse response = mcpService.execute(mcpRequest);
        if (response == null) {
            return StepExecutionResult.failed("MCP 执行失败：工具未返回响应", "mcp-empty-response");
        }
        if (!response.isSuccess()) {
            return StepExecutionResult.failed("MCP 执行失败：" + response.getErrorMessage(), response.getErrorCode());
        }

        String textResult = StrUtil.blankToDefault(response.getTextResult(), "MCP 执行成功");
        appendEvidence(evidence, "mcp-step", textResult, null);
        return StepExecutionResult.success("MCP 执行成功", List.of(), textResult);
    }

    private void enrichWriteParams(String toolId,
                                   String question,
                                   String evidence,
                                   Map<String, Object> params) {
        if ("obsidian_create".equals(toolId) && isBlankParam(params.get("name"))) {
            String explicitNoteName = extractExplicitNoteName(question);
            if (StrUtil.isNotBlank(explicitNoteName)) {
                params.put("name", explicitNoteName);
            }
        }
        if (StrUtil.isBlank(evidence)) {
            return;
        }
        if (isBlankParam(params.get("content")) || isRawQuestionContent(params.get("content"), question)) {
            String draftContent = buildWriteDraftContent(question, evidence);
            if (StrUtil.isNotBlank(draftContent)) {
                params.put("content", draftContent);
            }
        }
    }

    private boolean isBlankParam(Object value) {
        return value == null || StrUtil.isBlank(String.valueOf(value));
    }

    private boolean isRawQuestionContent(Object contentValue, String question) {
        if (contentValue == null || StrUtil.isBlank(question)) {
            return false;
        }
        return StrUtil.equals(StrUtil.trim(String.valueOf(contentValue)), StrUtil.trim(question));
    }

    private String extractExplicitNoteName(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        Matcher matcher = NOTE_TITLE_PATTERN.matcher(text);
        if (matcher.find()) {
            return StrUtil.trim(matcher.group(1));
        }
        return null;
    }

    private String buildWriteDraftContent(String question, String evidence) {
        String prompt = """
                请根据以下证据，为 Obsidian 笔记生成可直接写入的 Markdown 正文。
                要求：
                1) 只输出 Markdown 正文；
                2) 保留关键事实、列表、来源链接和结论；
                3) 不要编造证据中不存在的信息；
                4) 结构要贴合用户任务（如日报、简报、检查清单、复习计划等）。

                用户问题：
                %s

                当前证据：
                %s
                """.formatted(question, evidence);
        return llmService.chat(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是严谨的 Markdown 笔记整理助手。"),
                        ChatMessage.user(prompt)
                ))
                .thinking(false)
                .temperature(0.2D)
                .topP(0.8D)
                .maxTokens(1200)
                .build());
    }

    private StepExecutionResult executeSynthesize(AgentPlanStep step,
                                                  AgentExecuteRequest request,
                                                  StringBuilder evidence) {
        if (StrUtil.isBlank(evidence.toString())) {
            return StepExecutionResult.failed("证据为空，无法汇总", "no-evidence");
        }
        String prompt = """
                请根据以下证据进行中间汇总，保留关键事实并标注不确定项：
                %s
                """.formatted(evidence);
        String summary = llmService.chat(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是严谨的信息整合助手。"),
                        ChatMessage.user(prompt)
                ))
                .thinking(false)
                .temperature(0.2D)
                .topP(0.8D)
                .maxTokens(1200)
                .build());
        appendEvidence(evidence, "synthesize-step", summary, null);
        return StepExecutionResult.success("中间汇总完成", List.of(), summary);
    }

    private String resolveToolId(AgentPlanStep step, AgentExecuteRequest request) {
        String candidateToolId = StrUtil.trim(step.toolId());
        if (StrUtil.isNotBlank(candidateToolId)
                && mcpToolRegistry.getExecutor(candidateToolId).isPresent()) {
            return candidateToolId;
        }
        if (request.subIntents() != null) {
            for (SubQuestionIntent subQuestionIntent : request.subIntents()) {
                if (subQuestionIntent.nodeScores() == null) {
                    continue;
                }
                for (NodeScore nodeScore : subQuestionIntent.nodeScores()) {
                    if (nodeScore == null || nodeScore.getNode() == null) {
                        continue;
                    }
                    IntentNode node = nodeScore.getNode();
                    if (node.getKind() == IntentKind.MCP && StrUtil.isNotBlank(node.getMcpToolId())) {
                        return node.getMcpToolId();
                    }
                }
            }
        }
        return StrUtil.isBlank(candidateToolId) ? null : candidateToolId;
    }

    private String resolveLatestToolId(List<ActionRecord> actionHistory) {
        if (CollUtil.isEmpty(actionHistory)) {
            return null;
        }
        for (int i = actionHistory.size() - 1; i >= 0; i--) {
            ActionRecord record = actionHistory.get(i);
            if (record.step() == null || StrUtil.isBlank(record.step().toolId())) {
                continue;
            }
            return record.step().toolId();
        }
        return null;
    }

    private boolean isMcpExecutionAllowed(String toolId, AgentExecuteRequest request) {
        if (StrUtil.isBlank(toolId)) {
            return false;
        }
        if (isWriteTool(toolId) && isLikelyNoteWriteQuestion(request.question())) {
            return true;
        }
        if (CollUtil.isEmpty(request.subIntents())) {
            return true;
        }
        double bestScore = request.subIntents().stream()
                .filter(subIntent -> CollUtil.isNotEmpty(subIntent.nodeScores()))
                .flatMap(subIntent -> subIntent.nodeScores().stream())
                .filter(nodeScore -> nodeScore != null && nodeScore.getNode() != null)
                .filter(nodeScore -> nodeScore.getNode().getKind() == IntentKind.MCP)
                .filter(nodeScore -> StrUtil.equals(toolId, nodeScore.getNode().getMcpToolId()))
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0D);
        return bestScore >= MCP_EXECUTION_MIN_INTENT_SCORE;
    }

    private boolean isWriteTool(String toolId) {
        return mcpToolRegistry.getExecutor(toolId)
                .map(MCPToolExecutor::getToolDefinition)
                .map(tool -> isWriteTool(toolId, tool))
                .orElseGet(() -> isKnownWriteToolId(toolId));
    }

    private boolean isWriteTool(String toolId, MCPTool tool) {
        if (tool != null && tool.getOperationType() == MCPTool.OperationType.WRITE) {
            return true;
        }
        return isKnownWriteToolId(toolId);
    }

    private boolean isKnownWriteToolId(String toolId) {
        return switch (StrUtil.blankToDefault(toolId, "").trim()) {
            case "obsidian_create", "obsidian_update", "obsidian_replace", "obsidian_delete", "obsidian_video_transcript" -> true;
            default -> false;
        };
    }

    private boolean isLikelyNoteWriteQuestion(String question) {
        return NoteWriteIntentHelper.isLikelyNoteWriteQuestion(question);
    }

    private String suggestWriteToolId(String question) {
        return NoteWriteIntentHelper.suggestWriteToolId(question);
    }

    private String resolveTargetPath(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Object path = params.get("path");
        if (path != null) {
            return path.toString();
        }
        Object notePath = params.get("notePath");
        return notePath == null ? null : notePath.toString();
    }

    private String synthesizeFinalAnswer(String question, String evidence, String statusHint) {
        String prompt = """
                用户问题：
                %s

                执行状态：
                %s

                证据：
                %s

                请给出最终回答。要求：
                1) 先给结论，再给依据；
                2) 如果仍有未完成项，明确说明；
                3) 不编造事实。
                """.formatted(question, statusHint, evidence);
        return llmService.chat(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system("你是企业知识助手，请给出可执行、可验证的回答。"),
                        ChatMessage.user(prompt)
                ))
                .thinking(false)
                .temperature(Optional.ofNullable(ragConfigProperties.getChatKbTemperature()).orElse(0.3D))
                .topP(Optional.ofNullable(ragConfigProperties.getChatKbTopP()).orElse(0.85D))
                .maxTokens(Optional.ofNullable(ragConfigProperties.getChatMaxTokensKb()).orElse(2048))
                .build());
    }

    private void appendEvidence(StringBuilder evidence, String source, String kb, String mcp) {
        if (StrUtil.isNotBlank(kb)) {
            evidence.append("\n[").append(source).append(":kb]\n").append(kb).append("\n");
        }
        if (StrUtil.isNotBlank(mcp)) {
            evidence.append("\n[").append(source).append(":mcp]\n").append(mcp).append("\n");
        }
    }

    private void sendObservation(SseEmitter emitter, AgentObservePayload payload) {
        if (payload == null) {
            return;
        }
        sendEvent(emitter, SSEEventType.AGENT_OBSERVE.value(), payload);
    }

    private void sendAgentPlan(SseEmitter emitter, int loop, int startStepIndex, AgentPlan plan) {
        List<AgentPlanPayload.PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            AgentPlanStep step = plan.steps().get(i);
            steps.add(new AgentPlanPayload.PlanStep(startStepIndex + i, step.type(), step.instruction()));
        }
        sendEvent(emitter, SSEEventType.AGENT_PLAN.value(), new AgentPlanPayload(loop, plan.goal(), steps));
    }

    private void sendAgentStep(SseEmitter emitter,
                               int loop,
                               int stepIndex,
                               AgentPlanStep step,
                               StepExecutionResult result) {
        sendEvent(emitter, SSEEventType.AGENT_STEP.value(), new AgentStepPayload(
                loop,
                stepIndex,
                step.type(),
                result.confirmRequired() ? "CONFIRM_REQUIRED" : (result.success() ? "SUCCESS" : "FAILED"),
                result.summary(),
                result.references(),
                result.error()
        ));
    }

    private void sendReplan(SseEmitter emitter, int loop, ReflectionResult reflection) {
        List<String> nextSteps = reflection.nextSteps() == null
                ? List.of()
                : reflection.nextSteps().stream().map(AgentPlanStep::instruction).toList();
        sendEvent(emitter, SSEEventType.AGENT_REPLAN.value(), new AgentReplanPayload(loop, reflection.reason(), nextSteps));
    }

    private void sendConfirmRequired(SseEmitter emitter, PendingProposal proposal) {
        if (proposal == null) {
            return;
        }
        sendEvent(emitter, SSEEventType.AGENT_CONFIRM_REQUIRED.value(), new AgentConfirmPayload(
                proposal.getProposalId(),
                proposal.getToolId(),
                proposal.getParameters(),
                proposal.getTargetPath(),
                proposal.getRiskHint(),
                proposal.getExpiresAt()
        ));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            log.warn("发送 Agent SSE 事件失败: {}", eventName, e);
        }
    }

    private List<ReferenceItem> buildReferences(RetrievalContext ctx) {
        if (ctx == null || ctx.getIntentChunks() == null || ctx.getIntentChunks().isEmpty()) {
            return List.of();
        }
        Map<String, List<RetrievedChunk>> chunksByDoc = new LinkedHashMap<>();
        for (List<RetrievedChunk> chunks : ctx.getIntentChunks().values()) {
            if (chunks == null) {
                continue;
            }
            for (RetrievedChunk chunk : chunks) {
                if (chunk == null || StrUtil.isBlank(chunk.getDocumentId())) {
                    continue;
                }
                chunksByDoc.computeIfAbsent(chunk.getDocumentId(), k -> new ArrayList<>()).add(chunk);
            }
        }
        List<ReferenceItem> references = new ArrayList<>();
        for (Map.Entry<String, List<RetrievedChunk>> entry : chunksByDoc.entrySet()) {
            List<RetrievedChunk> chunks = entry.getValue();
            chunks.sort((a, b) -> Float.compare(
                    b.getScore() == null ? 0F : b.getScore(),
                    a.getScore() == null ? 0F : a.getScore()
            ));
            RetrievedChunk best = chunks.get(0);
            List<ReferenceItem.ChunkDetail> details = chunks.stream()
                    .limit(3)
                    .map(each -> new ReferenceItem.ChunkDetail(each.getText(), each.getScore()))
                    .toList();
            references.add(new ReferenceItem(
                    entry.getKey(),
                    null,
                    best.getKbId(),
                    null,
                    best.getScore(),
                    null,
                    best.getText(),
                    details
            ));
        }
        return references;
    }

    private Object parseJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isFloat() || node.isDouble() || node.isBigDecimal()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(each -> result.add(parseJsonNode(each)));
            return result;
        }
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), parseJsonNode(entry.getValue())));
            return result;
        }
        return node.toString();
    }

    private String normalizeStepType(String type) {
        if (StrUtil.isBlank(type)) {
            return "KB_RETRIEVE";
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "KB", "KB_RETRIEVE" -> "KB_RETRIEVE";
            case "GRAPH_QUERY", "GRAPH", "KG_QUERY" -> "GRAPH_QUERY";
            case "MCP", "MCP_CALL" -> "MCP_CALL";
            case "SYNTHESIZE", "SUMMARY" -> "SYNTHESIZE";
            default -> normalized;
        };
    }

    private String safeText(String text) {
        return StrUtil.blankToDefault(text, "无");
    }

    private String clip(String text, int maxChars) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String normalized = text.replace("\r", "").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private boolean isDateTimeLookupQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = StrUtil.trim(question);
        boolean dateTimeLookup = DATE_TIME_LOOKUP_HINT.matcher(normalized).find();
        if (!dateTimeLookup) {
            return false;
        }
        return !NoteWriteIntentHelper.isLikelyNoteWriteQuestion(normalized);
    }

    private record AgentPlan(String goal, List<AgentPlanStep> steps) {
    }

    private record AgentPlanStep(
            String type,
            String instruction,
            String query,
            String toolId,
            Map<String, Object> params) {
    }

    private record ReflectionResult(
            boolean done,
            String reason,
            List<AgentPlanStep> nextSteps) {
    }

    private record ReasoningDecision(
            String goal,
            String thought,
            boolean done,
            String finalAnswer,
            AgentPlanStep action) {
    }

    private record AgentObservation(
            int loop,
            int stepIndex,
            String source,
            String status,
            String summary,
            String detail) {
    }

    private record ActionRecord(
            int loop,
            int stepIndex,
            AgentPlanStep step,
            boolean success,
            String summary,
            String error) {
    }

    private record StepExecutionResult(
            boolean success,
            String summary,
            String error,
            List<ReferenceItem> references,
            boolean confirmRequired,
            PendingProposal proposal,
            String observationDetail,
            String proposalSummary) {

        static StepExecutionResult success(String summary,
                                           List<ReferenceItem> references,
                                           String observationDetail) {
            return new StepExecutionResult(true, summary, null, references, false, null, observationDetail, null);
        }

        static StepExecutionResult failed(String summary, String error) {
            return new StepExecutionResult(false, summary, error, List.of(), false, null, error, null);
        }

        static StepExecutionResult confirmRequired(String summary,
                                                   PendingProposal proposal,
                                                   String proposalSummary) {
            return new StepExecutionResult(true, summary, null, List.of(), true, proposal, null, proposalSummary);
        }
    }

    @Builder
    public record AgentExecuteRequest(
            String question,
            String conversationId,
            String userId,
            List<ChatMessage> history,
            List<SubQuestionIntent> subIntents,
            RetrievalContext firstRoundContext,
            SseEmitter emitter,
            StreamCallback callback,
            CancellationToken token) {
    }
}
