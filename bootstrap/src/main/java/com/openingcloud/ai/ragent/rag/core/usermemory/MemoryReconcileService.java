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

package com.openingcloud.ai.ragent.rag.core.usermemory;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryType;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.ReconcileAction;
import com.openingcloud.ai.ragent.rag.dao.entity.UserMemoryDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.MEMORY_RECONCILE_PROMPT_PATH;

/**
 * 向量搜索相似记忆 + LLM 决策 ADD/UPDATE/DELETE/NOOP
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryReconcileService {

    private final MemoryVectorStoreService vectorStoreService;
    private final UserMemoryService userMemoryService;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final UserMemoryProperties properties;

    /**
     * 调和新记忆与已有记忆：搜索相似 → LLM 决策 → 执行动作
     *
     * @return 最终保存的记忆（可能是新建或更新的），null 表示被判定为 NOOP/DELETE
     */
    public UserMemoryDO reconcileAndSave(Long userId, MemoryType type, String content,
                                          String conversationId, Long messageId) {
        // 搜索相似记忆
        List<MemoryVectorStoreService.VectorSearchResult> similar =
                vectorStoreService.search(userId, content, 5);

        // 过滤高相似度的
        List<MemoryVectorStoreService.VectorSearchResult> candidates = similar.stream()
                .filter(r -> r.score() >= properties.getReconcileSimilarityThreshold())
                .toList();

        if (candidates.isEmpty()) {
            // 无相似记忆，直接新增
            return userMemoryService.save(userId, type, content, conversationId, messageId);
        }

        // 构造已有记忆列表
        StringBuilder existingMemories = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            existingMemories.append("[").append(i).append("] ")
                    .append(c.content())
                    .append(" (相似度: ").append(String.format("%.2f", c.score())).append(")\n");
        }

        // LLM 决策
        String prompt = promptTemplateLoader.render(MEMORY_RECONCILE_PROMPT_PATH,
                Map.of("new_memory", content,
                        "existing_memories", existingMemories.toString()));

        String response = llmService.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.1)
                .maxTokens(500)
                .build());

        if (StrUtil.isBlank(response)) {
            return userMemoryService.save(userId, type, content, conversationId, messageId);
        }

        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            var decision = JSONUtil.parseObj(json);
            String actionStr = decision.getStr("action", "ADD").toUpperCase();
            ReconcileAction action = ReconcileAction.valueOf(actionStr);

            switch (action) {
                case ADD:
                    return userMemoryService.save(userId, type, content, conversationId, messageId);
                case UPDATE:
                    int targetIndex = decision.getInt("target_index", 0);
                    if (targetIndex >= 0 && targetIndex < candidates.size()) {
                        Long oldMemoryId = candidates.get(targetIndex).memoryId();
                        UserMemoryDO newMemory = userMemoryService.save(
                                userId, type, content, conversationId, messageId);
                        userMemoryService.archive(oldMemoryId, newMemory.getId());
                        vectorStoreService.delete(oldMemoryId);
                        return newMemory;
                    }
                    return userMemoryService.save(userId, type, content, conversationId, messageId);
                case DELETE:
                case NOOP:
                    log.info("记忆调和决策: action={}, content={}", action, content);
                    return null;
                default:
                    return userMemoryService.save(userId, type, content, conversationId, messageId);
            }
        } catch (Exception e) {
            log.warn("解析调和决策失败，默认新增: {}", e.getMessage());
            return userMemoryService.save(userId, type, content, conversationId, messageId);
        }
    }
}
