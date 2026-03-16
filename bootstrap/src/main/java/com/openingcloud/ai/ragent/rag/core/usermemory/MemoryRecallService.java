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

import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryType;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.RecalledMemory;
import com.openingcloud.ai.ragent.rag.dao.entity.UserMemoryDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合召回：Milvus 向量搜索 + MySQL 关键词 → 加权排序
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallService {

    private final MemoryVectorStoreService vectorStoreService;
    private final UserMemoryService userMemoryService;
    private final UserMemoryProperties properties;

    public List<RecalledMemory> recall(Long userId, String query) {
        int topK = properties.getRecallTopK();

        // 向量搜索
        List<MemoryVectorStoreService.VectorSearchResult> vectorResults =
                vectorStoreService.search(userId, query, topK);

        // MySQL 全量加载 ACTIVE 记忆（用于兜底和补充）
        List<UserMemoryDO> allActive = userMemoryService.listActive(userId);
        Map<Long, UserMemoryDO> memoryMap = allActive.stream()
                .collect(Collectors.toMap(UserMemoryDO::getId, m -> m));

        Set<Long> seen = new HashSet<>();
        List<RecalledMemory> results = new ArrayList<>();

        // 先加入向量搜索结果
        for (var vr : vectorResults) {
            if (vr.memoryId() == null || seen.contains(vr.memoryId())) {
                continue;
            }
            seen.add(vr.memoryId());
            UserMemoryDO memory = memoryMap.get(vr.memoryId());
            if (memory == null) {
                continue;
            }
            double weight = memory.getWeight() != null ? memory.getWeight().doubleValue() : 1.0;
            results.add(RecalledMemory.builder()
                    .memoryId(memory.getId())
                    .content(vr.content() != null ? vr.content() : memory.getContent())
                    .memoryType(MemoryType.valueOf(memory.getMemoryType()))
                    .score(vr.score() * weight)
                    .weight(weight)
                    .build());
        }

        // 补充 PINNED 记忆（确保高权重记忆始终在场）
        for (UserMemoryDO memory : allActive) {
            if (seen.contains(memory.getId())) {
                continue;
            }
            if (MemoryType.PINNED.name().equals(memory.getMemoryType())) {
                double weight = memory.getWeight() != null ? memory.getWeight().doubleValue() : 1.2;
                results.add(RecalledMemory.builder()
                        .memoryId(memory.getId())
                        .content(memory.getContent())
                        .memoryType(MemoryType.PINNED)
                        .score(0.5 * weight)
                        .weight(weight)
                        .build());
                seen.add(memory.getId());
            }
        }

        // 按综合分排序，截断到 topK
        results.sort(Comparator.comparingDouble(RecalledMemory::getScore).reversed());
        if (results.size() > topK) {
            results = results.subList(0, topK);
        }

        return results;
    }

    public String formatForPrompt(List<RecalledMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 相关记忆\n");
        for (int i = 0; i < memories.size(); i++) {
            RecalledMemory m = memories.get(i);
            sb.append(i + 1).append(". [").append(m.getMemoryType().name()).append("] ")
                    .append(m.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
