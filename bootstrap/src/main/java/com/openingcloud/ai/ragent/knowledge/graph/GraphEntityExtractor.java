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

package com.openingcloud.ai.ragent.knowledge.graph;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.util.LLMResponseCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询实体识别器
 * 从用户查询中提取关键实体，用于图谱遍历
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.knowledge-graph", name = "enabled", havingValue = "true")
public class GraphEntityExtractor {

    private static final String SYSTEM_PROMPT = """
            你是一个实体提取器。从用户问题中提取关键实体名称（名词短语），用于知识图谱查询。
            规则：
            1) 最多提取 5 个实体；
            2) 每行输出一个实体名称，不要编号或标点；
            3) 优先提取专有名词、技术术语、概念名称；
            4) 不要输出额外说明文字。
            """;

    private final LLMService llmService;

    /**
     * 从查询中提取实体
     *
     * @param query 用户查询
     * @return 实体列表（最多 5 个）
     */
    public List<String> extractEntities(String query) {
        if (StrUtil.isBlank(query)) {
            return List.of();
        }
        try {
            String response = llmService.chat(ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(query)
                    ))
                    .thinking(false)
                    .temperature(0.1D)
                    .maxTokens(200)
                    .build());
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(response);
            return parseEntities(cleaned);
        } catch (Exception e) {
            log.warn("LLM 实体提取失败，降级为分词切词: {}", e.getMessage());
            return fallbackExtract(query);
        }
    }

    private List<String> parseEntities(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        List<String> entities = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (StrUtil.isNotBlank(trimmed) && trimmed.length() <= 50) {
                // 去除可能的编号前缀
                trimmed = trimmed.replaceFirst("^\\d+[.、)\\s]+", "").trim();
                if (StrUtil.isNotBlank(trimmed)) {
                    entities.add(trimmed);
                }
            }
            if (entities.size() >= 5) {
                break;
            }
        }
        return entities;
    }

    /**
     * 降级策略：按标点和空格分词
     */
    private List<String> fallbackExtract(String query) {
        List<String> tokens = new ArrayList<>();
        String[] parts = query.split("[，。？！、\\s,?.!]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2 && trimmed.length() <= 20) {
                tokens.add(trimmed);
            }
            if (tokens.size() >= 5) {
                break;
            }
        }
        return tokens;
    }
}
