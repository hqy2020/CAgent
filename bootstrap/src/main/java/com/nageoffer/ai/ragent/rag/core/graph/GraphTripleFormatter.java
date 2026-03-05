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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.graph.GraphTriple;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 三元组格式化器
 * 将图谱查询返回的三元组转化为 RetrievedChunk，以便进入后处理链
 */
@Component
@ConditionalOnProperty(prefix = "rag.knowledge-graph", name = "enabled", havingValue = "true")
public class GraphTripleFormatter {

    /**
     * 将三元组按 docId 分组后格式化为 RetrievedChunk 列表
     */
    public List<RetrievedChunk> toRetrievedChunks(List<GraphTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return List.of();
        }

        // 按 docId 分组
        Map<String, List<GraphTriple>> grouped = triples.stream()
                .collect(Collectors.groupingBy(
                        t -> StrUtil.blankToDefault(t.docId(), "unknown"),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<RetrievedChunk> chunks = new ArrayList<>();
        for (Map.Entry<String, List<GraphTriple>> entry : grouped.entrySet()) {
            String docId = entry.getKey();
            List<GraphTriple> docTriples = entry.getValue();

            String text = docTriples.stream()
                    .map(t -> t.subject() + " " + t.relation() + " " + t.object())
                    .collect(Collectors.joining("；", "[知识图谱] ", ""));

            chunks.add(RetrievedChunk.builder()
                    .id("graph-" + docId)
                    .text(text)
                    .score(0.8f)
                    .documentId(docId)
                    .build());
        }
        return chunks;
    }
}
