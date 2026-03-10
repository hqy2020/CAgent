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

package com.openingcloud.ai.ragent.rag.core.retrieve.channel;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.knowledge.graph.GraphEntityExtractor;
import com.openingcloud.ai.ragent.knowledge.graph.GraphRepository;
import com.openingcloud.ai.ragent.knowledge.graph.GraphTriple;
import com.openingcloud.ai.ragent.rag.config.KnowledgeGraphProperties;
import com.openingcloud.ai.ragent.rag.core.graph.GraphTripleFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识图谱检索通道
 * 通过实体识别 + 图遍历获取结构化关系信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.knowledge-graph", name = "enabled", havingValue = "true")
public class GraphSearchChannel implements SearchChannel {

    private final KnowledgeGraphProperties graphProperties;
    private final GraphEntityExtractor entityExtractor;
    private final GraphRepository graphRepository;
    private final GraphTripleFormatter formatter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public String getName() {
        return "GraphSearch";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return graphProperties.isSearchChannelEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行知识图谱检索，问题：{}", context.getMainQuestion());

            // 1. 实体识别
            List<String> entities = entityExtractor.extractEntities(context.getMainQuestion());
            if (entities.isEmpty()) {
                log.info("未识别出实体，跳过图谱检索");
                return emptyResult(startTime);
            }
            log.debug("识别出实体：{}", entities);

            // 2. 获取所有知识库 ID
            List<String> kbIds = getAllKbCollections();
            if (kbIds.isEmpty()) {
                return emptyResult(startTime);
            }

            // 3. 逐库遍历图谱
            List<GraphTriple> allTriples = new ArrayList<>();
            for (String kbId : kbIds) {
                List<GraphTriple> triples = graphRepository.traverseByEntities(
                        kbId,
                        entities,
                        graphProperties.getTraversalMaxHops(),
                        graphProperties.getTraversalMaxNodes()
                );
                allTriples.addAll(triples);
            }

            if (allTriples.isEmpty()) {
                log.info("图谱检索无结果");
                return emptyResult(startTime);
            }

            // 4. 转换为 RetrievedChunk
            List<RetrievedChunk> chunks = formatter.toRetrievedChunks(allTriples);
            long latency = System.currentTimeMillis() - startTime;
            log.info("知识图谱检索完成，三元组 {} 条，Chunk {} 个，耗时 {}ms", allTriples.size(), chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KNOWLEDGE_GRAPH)
                    .channelName(getName())
                    .chunks(chunks)
                    .confidence(0.75)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("知识图谱检索失败", e);
            return emptyResult(startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KNOWLEDGE_GRAPH;
    }

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KNOWLEDGE_GRAPH)
                .channelName(getName())
                .chunks(List.of())
                .confidence(0.0)
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }

    private List<String> getAllKbCollections() {
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        List<String> collections = new ArrayList<>();
        for (KnowledgeBaseDO kb : kbList) {
            if (kb.getCollectionName() != null && !kb.getCollectionName().isBlank()) {
                collections.add(kb.getCollectionName());
            }
        }
        return collections;
    }
}
