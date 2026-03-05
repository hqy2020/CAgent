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

package com.nageoffer.ai.ragent.rag.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.knowledge.graph.GraphEntityExtractor;
import com.nageoffer.ai.ragent.knowledge.graph.GraphRepository;
import com.nageoffer.ai.ragent.knowledge.graph.GraphTriple;
import com.nageoffer.ai.ragent.rag.config.KnowledgeGraphProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.graph.GraphTripleFormatter;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;
import com.nageoffer.ai.ragent.rag.dto.AgentConfirmPayload;
import com.nageoffer.ai.ragent.rag.dto.AgentPlanPayload;
import com.nageoffer.ai.ragent.rag.dto.AgentReplanPayload;
import com.nageoffer.ai.ragent.rag.dto.AgentStepPayload;
import com.nageoffer.ai.ragent.rag.dto.ReferenceItem;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * Agent 编排器（Planner -> Executor -> Replan）
 */
@Slf4j
@Component
public class AgentOrchestrator {

    private static final double MCP_EXECUTION_MIN_INTENT_SCORE = 0.60D;

    private static final Set<String> WRITE_TOOL_IDS = Set.of(
            "obsidian_create",
            "obsidian_update",
            "obsidian_replace",
            "obsidian_delete",
            "obsidian_video_transcript"
    );

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是一个任务规划器。请基于用户问题输出严格 JSON：
            {
              "goal":"目标",
              "steps":[
                {"type":"KB_RETRIEVE|GRAPH_QUERY|MCP_CALL|SYNTHESIZE","instruction":"步骤说明","query":"检索/工具查询词","toolId":"可选工具ID","params":{}}
              ]
            }
            规则：
            1) steps 不超过 6 步；
            2) 必须至少包含 1 个可执行步骤；
            3) KB_RETRIEVE：知识库向量检索，适合语义相似性匹配；
            4) GRAPH_QUERY：知识图谱查询，适合实体关系推断、多跳关联查询；
            5) MCP_CALL：调用外部工具；
            6) SYNTHESIZE：汇总已有证据；
            7) JSON 之外不要输出额外文本。
            """;

    private static final String REFLECTOR_SYSTEM_PROMPT = """
            你是一个任务反思器。请输出严格 JSON：
            {
              "done": true/false,
              "reason": "判断原因",
              "nextSteps":[
                {"type":"KB_RETRIEVE|GRAPH_QUERY|MCP_CALL|SYNTHESIZE","instruction":"步骤说明","query":"查询词","toolId":"可选工具ID","params":{}}
              ]
            }
            规则：
            1) done=true 表示已可直接回答用户；
            2) done=false 时给出 nextSteps（不超过 6 步）；
            3) JSON 之外不要输出额外文本。
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
            int maxSteps = Math.max(1, Optional.ofNullable(ragConfigProperties.getAgentMaxStepsPerLoop()).orElse(6));

            StringBuilder evidence = new StringBuilder();
            if (request.firstRoundContext() != null) {
                appendEvidence(evidence, "first-round", request.firstRoundContext().getKbContext(), request.firstRoundContext().getMcpContext());
            }

            List<AgentPlanStep> candidateSteps = null;
            String latestReason = null;

            for (int loop = 1; loop <= maxLoops; loop++) {
                request.token().throwIfCancelled();

                AgentPlan plan = (candidateSteps == null || candidateSteps.isEmpty())
                        ? plan(loop, request.question(), evidence.toString(), latestReason, maxSteps)
                        : new AgentPlan(request.question(), candidateSteps);
                plan = trimPlan(plan, maxSteps);

                sendAgentPlan(request.emitter(), loop, plan);

                List<String> stepSummaries = new ArrayList<>();
                boolean loopFailed = false;

                for (int idx = 0; idx < plan.steps().size(); idx++) {
                    request.token().throwIfCancelled();
                    AgentPlanStep step = plan.steps().get(idx);

                    StepExecutionResult result = null;
                    Exception lastError = null;
                    for (int attempt = 1; attempt <= 2; attempt++) {
                        try {
                            result = executeStep(step, request, evidence, loop, idx + 1);
                            break;
                        } catch (Exception e) {
                            lastError = e;
                            log.warn("Agent 步骤执行失败，准备重试。loop={}, step={}, attempt={}", loop, idx + 1, attempt, e);
                        }
                    }

                    if (result == null && lastError != null) {
                        result = StepExecutionResult.failed("步骤执行失败：" + lastError.getMessage(), lastError.getMessage());
                    }

                    if (result == null) {
                        result = StepExecutionResult.failed("步骤执行失败：未知错误", "unknown");
                    }

                    sendAgentStep(request.emitter(), loop, idx + 1, step, result);
                    stepSummaries.add("step-" + (idx + 1) + ": " + result.summary());

                    if (result.confirmRequired()) {
                        PendingProposal proposal = result.proposal();
                        sendConfirmRequired(request.emitter(), proposal);
                        request.callback().onContent("检测到写操作，需要你确认后才会执行。\n"
                                + "请输入 `/confirm " + proposal.getProposalId() + "` 执行，或 `/reject " + proposal.getProposalId() + "` 取消。");
                        request.callback().onComplete();
                        return true;
                    }

                    if (!result.success()) {
                        loopFailed = true;
                    }
                }

                ReflectionResult reflection = reflect(loop, request.question(), evidence.toString(), stepSummaries, maxSteps);
                if (reflection.done() && !StrUtil.isBlank(evidence.toString())) {
                    String answer = synthesizeFinalAnswer(request.question(), evidence.toString(), "任务已完成");
                    request.callback().onContent(answer);
                    request.callback().onComplete();
                    return true;
                }

                latestReason = reflection.reason();
                candidateSteps = reflection.nextSteps();
                sendReplan(request.emitter(), loop, reflection);

                if (!loopFailed && (candidateSteps == null || candidateSteps.isEmpty())) {
                    break;
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

    @RagTraceNode(name = "agent-replan", type = "AGENT_REPLAN")
    protected ReflectionResult reflect(int loop,
                                       String question,
                                       String evidence,
                                       List<String> stepSummaries,
                                       int maxSteps) {
        String userPrompt = """
                用户问题：
                %s

                当前证据：
                %s

                本轮执行摘要：
                %s
                """.formatted(question, safeText(evidence), String.join("\n", stepSummaries));
        String raw = llmService.chat(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(REFLECTOR_SYSTEM_PROMPT),
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
            boolean done = root.path("done").asBoolean(false);
            String reason = root.path("reason").asText(done ? "已可回答" : "需要补充信息");
            List<AgentPlanStep> next = parseSteps(root.path("nextSteps"), maxSteps, question);
            return new ReflectionResult(done, reason, next);
        } catch (Exception e) {
            boolean done = StrUtil.length(evidence) > 180;
            String reason = done ? "反思解析失败，按现有证据结束" : "反思解析失败，尝试补充一次 KB 检索";
            List<AgentPlanStep> next = done ? List.of() : List.of(new AgentPlanStep("KB_RETRIEVE", "补充检索证据", question, null, Map.of()));
            return new ReflectionResult(done, reason, next);
        }
    }

    private AgentPlan plan(int loop, String question, String evidence, String reason, int maxSteps) {
        String userPrompt = """
                用户问题：
                %s

                已有证据：
                %s

                上一轮原因：
                %s
                """.formatted(question, safeText(evidence), safeText(reason));

        String raw = llmService.chat(ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(PLANNER_SYSTEM_PROMPT),
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
            List<AgentPlanStep> steps = parseSteps(root.path("steps"), maxSteps, question);
            if (steps.isEmpty()) {
                steps = defaultPlan(question);
            }
            return new AgentPlan(goal, steps);
        } catch (Exception e) {
            return new AgentPlan(question, defaultPlan(question));
        }
    }

    private AgentPlan trimPlan(AgentPlan plan, int maxSteps) {
        List<AgentPlanStep> steps = plan.steps();
        if (steps == null || steps.isEmpty()) {
            return new AgentPlan(plan.goal(), defaultPlan(plan.goal()));
        }
        return new AgentPlan(plan.goal(), steps.stream().limit(maxSteps).toList());
    }

    private List<AgentPlanStep> parseSteps(JsonNode stepsNode, int maxSteps, String fallbackQuery) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return List.of();
        }
        List<AgentPlanStep> steps = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            String type = normalizeStepType(node.path("type").asText("KB_RETRIEVE"));
            String instruction = node.path("instruction").asText("执行步骤");
            String query = node.path("query").asText(fallbackQuery);
            String toolId = node.path("toolId").asText(null);
            Map<String, Object> params = new LinkedHashMap<>();
            JsonNode paramsNode = node.path("params");
            if (paramsNode.isObject()) {
                paramsNode.fields().forEachRemaining(entry -> params.put(entry.getKey(), parseJsonNode(entry.getValue())));
            }
            steps.add(new AgentPlanStep(type, instruction, query, toolId, params));
            if (steps.size() >= maxSteps) {
                break;
            }
        }
        return steps;
    }

    private List<AgentPlanStep> defaultPlan(String query) {
        return List.of(
                new AgentPlanStep("KB_RETRIEVE", "补充知识库检索", query, null, Map.of()),
                new AgentPlanStep("SYNTHESIZE", "汇总并回答用户", query, null, Map.of())
        );
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
        if (kbIntents.isEmpty()) {
            return StepExecutionResult.failed("未识别到可用 KB 意图", "kb-intent-empty");
        }
        RetrievalContext context = retrievalEngine.retrieve(kbIntents, DEFAULT_TOP_K, request.token());
        if (context == null || context.isEmpty()) {
            return StepExecutionResult.failed("KB 检索无结果", "kb-empty");
        }
        appendEvidence(evidence, "kb-step", context.getKbContext(), null);
        List<ReferenceItem> references = buildReferences(context);
        return StepExecutionResult.success("KB 检索完成，已获得相关证据", references);
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
        return StepExecutionResult.success("图谱查询完成，发现 " + allTriples.size() + " 条关系", List.of());
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
        if (!isMcpExecutionAllowed(toolId, request)) {
            return StepExecutionResult.failed("MCP 执行已跳过：缺少高置信度意图支持", "mcp-confidence-low");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (step.params() != null && !step.params().isEmpty()) {
            params.putAll(step.params());
        } else {
            params.putAll(mcpParameterExtractor.extractParameters(query, tool, null));
        }

        MCPRequest mcpRequest = MCPRequest.builder()
                .toolId(toolId)
                .userId(request.userId())
                .conversationId(request.conversationId())
                .userQuestion(query)
                .parameters(params)
                .build();

        if (WRITE_TOOL_IDS.contains(toolId)) {
            PendingProposal proposal = pendingProposalStore.create(
                    request.userId(),
                    request.conversationId(),
                    mcpRequest,
                    resolveTargetPath(params),
                    "写操作默认需要人工确认，防止误写入"
            );
            return StepExecutionResult.confirmRequired("检测到写操作，已创建待确认提案", proposal);
        }

        MCPResponse response = mcpService.execute(mcpRequest);
        if (!response.isSuccess()) {
            return StepExecutionResult.failed("MCP 执行失败：" + response.getErrorMessage(), response.getErrorCode());
        }

        String textResult = StrUtil.blankToDefault(response.getTextResult(), "MCP 执行成功");
        appendEvidence(evidence, "mcp-step", textResult, null);
        return StepExecutionResult.success("MCP 执行成功", List.of());
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
        return StepExecutionResult.success("中间汇总完成", List.of());
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

    private boolean isMcpExecutionAllowed(String toolId, AgentExecuteRequest request) {
        if (StrUtil.isBlank(toolId)) {
            return false;
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

    private void sendAgentPlan(SseEmitter emitter, int loop, AgentPlan plan) {
        List<AgentPlanPayload.PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            AgentPlanStep step = plan.steps().get(i);
            steps.add(new AgentPlanPayload.PlanStep(i + 1, step.type(), step.instruction()));
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

    private record StepExecutionResult(
            boolean success,
            String summary,
            String error,
            List<ReferenceItem> references,
            boolean confirmRequired,
            PendingProposal proposal) {

        static StepExecutionResult success(String summary, List<ReferenceItem> references) {
            return new StepExecutionResult(true, summary, null, references, false, null);
        }

        static StepExecutionResult failed(String summary, String error) {
            return new StepExecutionResult(false, summary, error, List.of(), false, null);
        }

        static StepExecutionResult confirmRequired(String summary, PendingProposal proposal) {
            return new StepExecutionResult(true, summary, null, List.of(), true, proposal);
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
