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

package com.openingcloud.ai.ragent.rag.skill;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandle;
import com.openingcloud.ai.ragent.infra.chat.StreamCancellationHandles;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.util.LLMResponseCleaner;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.dto.AgentStepPayload;
import com.openingcloud.ai.ragent.rag.enums.SSEEventType;
import com.openingcloud.ai.ragent.rag.exception.TaskCancelledException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill-Based RAG 编排器（v4 核心）
 * <p>
 * 替代 v3 的 pipeline 编排，让 AI 自主决定检索策略：
 * 1. 构建 system prompt（KB 目录 + 工具说明）
 * 2. 组装消息（system + history + user question）
 * 3. 调用 LLM，解析 JSON 输出
 * 4. 如果 AI 请求工具调用 → 执行工具 → 追加结果 → 回到 3
 * 5. 如果 AI 输出最终回答 → 流式输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBasedRAGOrchestrator {

    private static final int MAX_TOOL_ROUNDS = 5;
    private static final String SKILL_SYSTEM_PROMPT_PATH = "prompt/skill/system.st";

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final PromptTemplateLoader promptTemplateLoader;
    private final KnowledgeCatalogService catalogService;
    private final SkillToolRegistry toolRegistry;
    private final SkillToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 执行 skill-based RAG 对话
     *
     * @param request 执行请求
     * @return 流式取消句柄（仅在最终回答流式输出时有效）
     */
    public StreamCancellationHandle execute(SkillExecuteRequest request) {
        try {
            // 构建 system prompt
            String catalog = catalogService.buildCatalogPrompt();
            String tools = toolRegistry.buildToolsPrompt();
            String systemPrompt = promptTemplateLoader.render(SKILL_SYSTEM_PROMPT_PATH, Map.of(
                    "catalog", catalog,
                    "tools", tools
            ));

            // 组装消息
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt));
            if (request.history() != null) {
                messages.addAll(request.history());
            }
            messages.add(ChatMessage.user(request.question()));

            // Tool-Use 循环
            for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
                request.token().throwIfCancelled();

                emitToolStep(request.emitter(), round, "思考中", "RUNNING",
                        "正在分析问题，决定检索策略...");

                // 同步调用 LLM 获取 JSON 决策
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(new ArrayList<>(messages))
                        .temperature(ragConfigProperties.getChatKbTemperature())
                        .topP(ragConfigProperties.getChatKbTopP())
                        .maxTokens(ragConfigProperties.getChatMaxTokensKb())
                        .thinking(false)
                        .build();

                String llmResponse = llmService.chat(chatRequest);
                if (StrUtil.isBlank(llmResponse)) {
                    log.warn("Skill RAG: LLM 返回空响应, round={}", round);
                    request.callback().onContent("抱歉，未能生成回答。");
                    request.callback().onComplete();
                    return StreamCancellationHandles.noop();
                }

                // 解析 JSON 决策
                SkillDecision decision = parseDecision(llmResponse);

                if (decision.type() == DecisionType.ANSWER) {
                    // 最终回答 → 流式输出
                    emitToolStep(request.emitter(), round, "生成回答", "SUCCESS",
                            "已完成分析，正在生成最终回答。");

                    String answerContent = decision.content();
                    if (StrUtil.isNotBlank(answerContent)) {
                        // 如果内容已完整，直接推送
                        request.callback().onContent(answerContent);
                        request.callback().onComplete();
                        return StreamCancellationHandles.noop();
                    } else {
                        // 回退：将整个 LLM 响应作为回答
                        request.callback().onContent(llmResponse);
                        request.callback().onComplete();
                        return StreamCancellationHandles.noop();
                    }
                }

                if (decision.type() == DecisionType.TOOL_CALL) {
                    // 执行工具调用
                    String toolName = decision.tool();
                    Map<String, Object> toolArgs = decision.args();

                    emitToolStep(request.emitter(), round, "调用工具: " + toolName, "RUNNING",
                            "正在执行 " + toolName + " ...");

                    request.token().throwIfCancelled();
                    SkillToolExecutor.ToolExecutionResult result =
                            toolExecutor.execute(toolName, toolArgs, request.token());

                    String status = result.success() ? "SUCCESS" : "FAILED";
                    String summary = result.success()
                            ? toolName + " 执行成功，已获取结果。"
                            : toolName + " 执行失败: " + result.content();
                    emitToolStep(request.emitter(), round, "工具结果: " + toolName, status, summary);

                    // 追加到消息历史继续循环
                    messages.add(ChatMessage.assistant(llmResponse));
                    messages.add(ChatMessage.user("工具执行结果：\n" + result.content()));
                    continue;
                }

                // 无法解析 → 当作最终回答
                log.warn("Skill RAG: 无法解析 LLM 决策，当作最终回答。round={}, response={}",
                        round, llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);
                request.callback().onContent(llmResponse);
                request.callback().onComplete();
                return StreamCancellationHandles.noop();
            }

            // 达到最大轮次
            log.warn("Skill RAG: 达到最大工具调用轮次 {}", MAX_TOOL_ROUNDS);
            emitToolStep(request.emitter(), MAX_TOOL_ROUNDS + 1, "生成回答", "RUNNING",
                    "已达到最大检索次数，正在基于当前信息生成回答。");

            // 最后一轮强制要求输出答案
            messages.add(ChatMessage.user("请根据已有信息直接给出最终回答，不要再调用工具。"));
            ChatRequest finalRequest = ChatRequest.builder()
                    .messages(new ArrayList<>(messages))
                    .temperature(ragConfigProperties.getChatKbTemperature())
                    .topP(ragConfigProperties.getChatKbTopP())
                    .maxTokens(ragConfigProperties.getChatMaxTokensKb())
                    .thinking(false)
                    .build();

            return llmService.streamChat(finalRequest, request.callback());
        } catch (TaskCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("Skill-Based RAG 编排执行失败", e);
            request.callback().onError(e);
            return StreamCancellationHandles.noop();
        }
    }

    private SkillDecision parseDecision(String llmResponse) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(llmResponse).trim();

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            String type = root.has("type") ? root.get("type").asText() : "";

            // 只有明确的 tool_call 才解析为工具调用
            if ("tool_call".equals(type)) {
                String tool = root.has("tool") ? root.get("tool").asText() : "";
                Map<String, Object> args = new HashMap<>();
                if (root.has("args") && root.get("args").isObject()) {
                    root.get("args").fields().forEachRemaining(entry -> {
                        JsonNode val = entry.getValue();
                        if (val.isInt()) {
                            args.put(entry.getKey(), val.intValue());
                        } else if (val.isNumber()) {
                            args.put(entry.getKey(), val.numberValue());
                        } else if (val.isBoolean()) {
                            args.put(entry.getKey(), val.booleanValue());
                        } else {
                            args.put(entry.getKey(), val.asText());
                        }
                    });
                }
                return new SkillDecision(DecisionType.TOOL_CALL, tool, args, null);
            }

            // 兼容旧格式：{"type":"answer","content":"..."}
            if ("answer".equals(type) && root.has("content")) {
                String content = root.get("content").asText();
                if (StrUtil.isNotBlank(content)) {
                    return new SkillDecision(DecisionType.ANSWER, null, null, content);
                }
            }
        } catch (Exception e) {
            log.debug("JSON 解析失败，当作自由文本回答: {}", e.getMessage());
        }

        // 非 JSON 或非 tool_call → 整个响应就是自然文本回答
        return new SkillDecision(DecisionType.ANSWER, null, null, llmResponse);
    }

    private void emitToolStep(SseEmitter emitter, int stepIndex, String type,
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
            log.warn("发送 Skill RAG 步骤事件失败, stepIndex={}, type={}", stepIndex, type, e);
        }
    }

    enum DecisionType {
        TOOL_CALL,
        ANSWER
    }

    record SkillDecision(DecisionType type, String tool, Map<String, Object> args, String content) {
    }

    @Builder
    public record SkillExecuteRequest(
            String question,
            String conversationId,
            String userId,
            List<ChatMessage> history,
            SseEmitter emitter,
            StreamCallback callback,
            CancellationToken token
    ) {
    }
}
