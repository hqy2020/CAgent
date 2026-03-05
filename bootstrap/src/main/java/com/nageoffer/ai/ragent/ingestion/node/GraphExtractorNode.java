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

package com.nageoffer.ai.ragent.ingestion.node;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.GraphExtractorSettings;
import com.nageoffer.ai.ragent.knowledge.graph.GraphRepository;
import com.nageoffer.ai.ragent.knowledge.graph.GraphTriple;
import com.nageoffer.ai.ragent.rag.config.KnowledgeGraphProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库图谱抽取节点
 * 从文档文本中抽取实体关系三元组，写入 Neo4j
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.knowledge-graph", name = "enabled", havingValue = "true")
public class GraphExtractorNode implements IngestionNode {

    private static final int MAX_TEXT_LENGTH = 8000;

    private static final String SYSTEM_PROMPT = """
            你是一个知识图谱三元组抽取器。从给定文本中提取实体关系三元组。
            输出严格 JSON 数组格式：
            [{"subject":"主体","relation":"关系","object":"客体"}]
            规则：
            1) 每个三元组包含 subject、relation、object 三个字段；
            2) 实体使用规范化名称（如"Spring IoC"而非"ioc"）；
            3) 关系使用简短动词或介词短语（如"包含"、"继承自"、"用于"）；
            4) 最多输出 %d 个三元组；
            5) JSON 之外不要输出额外文本。
            """;

    private final LLMService llmService;
    private final GraphRepository graphRepository;
    private final KnowledgeGraphProperties graphProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String getNodeType() {
        return IngestionNodeType.GRAPH_EXTRACTOR.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        String text = resolveText(context);
        if (StrUtil.isBlank(text)) {
            return NodeResult.skip("No text to extract graph triples from");
        }

        GraphExtractorSettings settings = parseSettings(config.getSettings());
        String kbId = resolveKbId(context);
        String docId = context.getTaskId();

        if (StrUtil.isBlank(kbId)) {
            return NodeResult.skip("No kbId available for graph extraction");
        }

        // 截断过长文本
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        // 先删除旧数据
        if (settings.isDeleteBeforeInsert()) {
            graphRepository.deleteByDocId(kbId, docId);
        }

        // LLM 抽取三元组
        int maxTriples = settings.getMaxTriples() > 0 ? settings.getMaxTriples() : graphProperties.getExtractMaxTriples();
        List<GraphTriple> triples = extractTriples(text, docId, maxTriples);

        if (triples.isEmpty()) {
            return NodeResult.ok("No triples extracted");
        }

        // 写入 Neo4j
        graphRepository.mergeTriples(kbId, docId, triples);
        return NodeResult.ok("Extracted and stored " + triples.size() + " graph triples");
    }

    private List<GraphTriple> extractTriples(String text, String docId, int maxTriples) {
        try {
            String response = llmService.chat(ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(SYSTEM_PROMPT.formatted(maxTriples)),
                            ChatMessage.user(text)
                    ))
                    .thinking(false)
                    .temperature(0.1D)
                    .maxTokens(2048)
                    .build());
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(response);
            return parseTriples(cleaned, docId);
        } catch (Exception e) {
            log.warn("图谱三元组抽取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<GraphTriple> parseTriples(String json, String docId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<GraphTriple> triples = new ArrayList<>();
            for (JsonNode node : root) {
                String subject = node.path("subject").asText("");
                String relation = node.path("relation").asText("");
                String object = node.path("object").asText("");
                if (StrUtil.isNotBlank(subject) && StrUtil.isNotBlank(relation) && StrUtil.isNotBlank(object)) {
                    triples.add(new GraphTriple(subject.trim(), relation.trim(), object.trim(), docId));
                }
            }
            return triples;
        } catch (Exception e) {
            log.warn("三元组 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveText(IngestionContext context) {
        if (StrUtil.isNotBlank(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    private String resolveKbId(IngestionContext context) {
        if (context.getVectorSpaceId() != null && StrUtil.isNotBlank(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        if (context.getMetadata() != null) {
            Object kbId = context.getMetadata().get("kbId");
            return kbId != null ? kbId.toString() : null;
        }
        return null;
    }

    private GraphExtractorSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return GraphExtractorSettings.builder().build();
        }
        return objectMapper.convertValue(node, GraphExtractorSettings.class);
    }
}
