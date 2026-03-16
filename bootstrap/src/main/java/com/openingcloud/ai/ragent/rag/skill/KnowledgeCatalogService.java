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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.ingestion.service.IntentTreeService;
import com.openingcloud.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KB 元数据目录服务（Skill-Based RAG Level 1）
 * <p>
 * 生成知识库目录文本，注入 system prompt，让 AI 知道有哪些知识库可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCatalogService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final IntentTreeService intentTreeService;

    /**
     * 生成知识库目录文本（用于注入 system prompt）
     *
     * @return Markdown 格式的知识库目录
     */
    public String buildCatalogPrompt() {
        List<IntentNodeTreeVO> tree = intentTreeService.getFullTree();
        if (CollUtil.isEmpty(tree)) {
            return "当前没有可用的知识库。";
        }

        // 收集所有 KB 类型的叶子节点
        Map<Long, KbCatalogEntry> entries = new HashMap<>();
        collectKbEntries(tree, entries);

        if (entries.isEmpty()) {
            return "当前没有可用的知识库。";
        }

        // 查询每个知识库的文档数
        Map<Long, Long> docCounts = countDocsByKb(entries.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用知识库\n");
        sb.append("| ID | 名称 | 描述 | 文档数 |\n");
        sb.append("|---|------|------|--------|\n");

        for (Map.Entry<Long, KbCatalogEntry> entry : entries.entrySet()) {
            Long kbId = entry.getKey();
            KbCatalogEntry catalog = entry.getValue();
            long docCount = docCounts.getOrDefault(kbId, 0L);
            sb.append("| ").append(kbId)
                    .append(" | ").append(catalog.name)
                    .append(" | ").append(StrUtil.blankToDefault(catalog.description, "-"))
                    .append(" | ").append(docCount)
                    .append(" |\n");
        }

        return sb.toString().trim();
    }

    private void collectKbEntries(List<IntentNodeTreeVO> nodes, Map<Long, KbCatalogEntry> entries) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        for (IntentNodeTreeVO node : nodes) {
            // KB 类型（kind=0 或 null 为 KB）
            boolean isKb = node.getKind() == null || node.getKind() == 0;
            boolean isEnabled = node.getEnabled() != null && node.getEnabled() == 1;
            if (isKb && isEnabled && StrUtil.isNotBlank(node.getCollectionName())) {
                // 从 collection name 找到对应的 KB
                KnowledgeBaseDO kb = findKbByCollection(node.getCollectionName());
                if (kb != null) {
                    entries.putIfAbsent(kb.getId(), new KbCatalogEntry(
                            kb.getName(),
                            StrUtil.blankToDefault(node.getDescription(), kb.getName()),
                            node.getCollectionName()
                    ));
                }
            }
            collectKbEntries(node.getChildren(), entries);
        }
    }

    private KnowledgeBaseDO findKbByCollection(String collectionName) {
        return knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
                        .last("LIMIT 1")
        );
    }

    private Map<Long, Long> countDocsByKb(java.util.Set<Long> kbIds) {
        Map<Long, Long> result = new HashMap<>();
        for (Long kbId : kbIds) {
            Long count = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, kbId)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            result.put(kbId, count);
        }
        return result;
    }

    private record KbCatalogEntry(String name, String description, String collectionName) {
    }
}
