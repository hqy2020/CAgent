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

package com.openingcloud.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_CORE_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_DETAILED_MODE_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_LINK_MEDIA_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_MULTI_QUESTION_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.MCP_KB_MIXED_PROMPT_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.MCP_ONLY_PROMPT_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.RAG_ENTERPRISE_PROMPT_PATH;

/**
 * RAG Prompt 编排服务
 * <p>
 * 根据检索结果场景（KB / MCP / Mixed）选择模板，并构造最终发送给 LLM 的消息序列
 */
@Service
@RequiredArgsConstructor
public class RAGPromptService {

    private static final String MCP_CONTEXT_HEADER = "## 动态数据片段";
    private static final String KB_CONTEXT_HEADER = "## 文档内容";
    private static final Pattern DETAIL_REQUEST_PATTERN = Pattern.compile(
            "详细|具体|展开|深入|原理|实现细节|逐步|举例|完整说明|深度分析"
    );
    private static final Pattern LINK_OR_IMAGE_PATTERN = Pattern.compile("(https?://|www\\.|!\\[)");

    private final PromptTemplateLoader promptTemplateLoader;
    private final RAGConfigProperties ragConfigProperties;

    /**
     * 生成系统提示词，并对模板格式做清理
     */
    public String buildSystemPrompt(PromptContext context) {
        return buildSystemPrompt(context, 1);
    }

    /**
     * 生成系统提示词（渐进式披露）
     * 第一层：核心规则；第二层：场景规则；第三层：按需细则
     */
    public String buildSystemPrompt(PromptContext context, int subQuestionCount) {
        PromptBuildPlan plan = plan(context);
        String sceneTemplate = StrUtil.isNotBlank(plan.getBaseTemplate())
                ? plan.getBaseTemplate()
                : defaultTemplate(plan.getScene());
        if (StrUtil.isBlank(sceneTemplate)) {
            return "";
        }

        if (!Boolean.TRUE.equals(ragConfigProperties.getPromptProgressiveEnabled())) {
            return PromptTemplateUtils.cleanupPrompt(sceneTemplate);
        }

        List<String> sections = new ArrayList<>(5);
        if (Boolean.TRUE.equals(ragConfigProperties.getPromptProgressiveCoreEnabled())) {
            sections.add(promptTemplateLoader.load(PROGRESSIVE_PROMPT_CORE_PATH));
        }
        sections.add(sceneTemplate);

        if (subQuestionCount > 1
                && Boolean.TRUE.equals(ragConfigProperties.getPromptProgressiveOptionalMultiQuestionEnabled())) {
            sections.add(promptTemplateLoader.load(PROGRESSIVE_PROMPT_MULTI_QUESTION_PATH));
        }
        if (Boolean.TRUE.equals(ragConfigProperties.getPromptProgressiveOptionalLinkMediaEnabled())
                && hasLinkOrImageEvidence(context)) {
            sections.add(promptTemplateLoader.load(PROGRESSIVE_PROMPT_LINK_MEDIA_PATH));
        }
        if (Boolean.TRUE.equals(ragConfigProperties.getPromptProgressiveOptionalDetailedModeEnabled())
                && isDetailedRequest(context.getQuestion())) {
            sections.add(promptTemplateLoader.load(PROGRESSIVE_PROMPT_DETAILED_MODE_PATH));
        }

        return PromptTemplateUtils.cleanupPrompt(String.join("\n\n", sections));
    }

    /**
     * 构造发送给 LLM 的完整消息列表（system + evidence + history + user）
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions) {
        List<ChatMessage> messages = new ArrayList<>();
        int subQuestionCount = CollUtil.isEmpty(subQuestions) ? 1 : subQuestions.size();
        String systemPrompt = buildSystemPrompt(context, subQuestionCount);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (StrUtil.isNotBlank(context.getMcpContext())) {
            messages.add(ChatMessage.system(formatEvidence(MCP_CONTEXT_HEADER, context.getMcpContext())));
        }
        if (StrUtil.isNotBlank(context.getKbContext())) {
            messages.add(ChatMessage.user(formatEvidence(KB_CONTEXT_HEADER, context.getKbContext())));
        }
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }

        // 多子问题场景下，显式编号以降低模型漏答风险
        if (CollUtil.isNotEmpty(subQuestions) && subQuestions.size() > 1) {
            StringBuilder userMessage = new StringBuilder();
            userMessage.append("请基于上述文档内容，回答以下问题：\n\n");
            for (int i = 0; i < subQuestions.size(); i++) {
                userMessage.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
            }
            messages.add(ChatMessage.user(userMessage.toString().trim()));
        } else if (StrUtil.isNotBlank(question)) {
            messages.add(ChatMessage.user(question));
        }

        return messages;
    }

    private PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;

        // 1) 先剔除“未命中检索”的意图
        List<NodeScore> retained = safeIntents.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    String key = nodeKey(node);
                    List<RetrievedChunk> chunks = intentChunks == null ? null : intentChunks.get(key);
                    return CollUtil.isNotEmpty(chunks);
                })
                .toList();

        if (retained.isEmpty()) {
            // 没有任何可用意图：无基模板（上层可根据业务选择 fallback）
            return new PromptPlan(Collections.emptyList(), null);
        }

        // 2) 单 / 多意图的模板与片段策略
        if (retained.size() == 1) {
            IntentNode only = retained.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();

            if (StrUtil.isNotBlank(tpl)) {
                // 单意图 + 有模板：使用模板本身
                return new PromptPlan(retained, tpl);
            } else {
                // 单意图 + 无模板：走默认模板
                return new PromptPlan(retained, null);
            }
        } else {
            // 多意图：统一默认模板
            return new PromptPlan(retained, null);
        }
    }

    private PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }
        throw new IllegalStateException("PromptContext requires MCP or KB context.");
    }

    private PromptBuildPlan planKbOnly(PromptContext context) {
        PromptPlan plan = planPrompt(context.getKbIntents(), context.getIntentChunks());
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private PromptBuildPlan planMixed(PromptContext context) {
        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private String defaultTemplate(PromptScene scene) {
        return switch (scene) {
            case KB_ONLY -> promptTemplateLoader.load(RAG_ENTERPRISE_PROMPT_PATH);
            case MCP_ONLY -> promptTemplateLoader.load(MCP_ONLY_PROMPT_PATH);
            case MIXED -> promptTemplateLoader.load(MCP_KB_MIXED_PROMPT_PATH);
            case EMPTY -> "";
        };
    }

    private String formatEvidence(String header, String body) {
        return header + "\n" + body.trim();
    }

    private boolean hasLinkOrImageEvidence(PromptContext context) {
        String mcp = StrUtil.emptyIfNull(context.getMcpContext());
        String kb = StrUtil.emptyIfNull(context.getKbContext());
        return LINK_OR_IMAGE_PATTERN.matcher(mcp).find() || LINK_OR_IMAGE_PATTERN.matcher(kb).find();
    }

    private boolean isDetailedRequest(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        return DETAIL_REQUEST_PATTERN.matcher(question).find();
    }

    // === 工具方法 ===

    /**
     * 从意图节点提取用于映射检索结果的 key
     */
    private static String nodeKey(IntentNode node) {
        if (node == null) return "";
        if (StrUtil.isNotBlank(node.getId())) return node.getId();
        return String.valueOf(node.getId());
    }

}
