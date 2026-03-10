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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱 Cypher DAO
 * 基于 Neo4j 原生 Driver 实现三元组的增删查
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.knowledge-graph", name = "enabled", havingValue = "true")
public class GraphRepository {

    private final Driver neo4jDriver;

    /**
     * 幂等写入三元组（MERGE 语义）
     */
    public void mergeTriples(String kbId, String docId, List<GraphTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        String cypher = """
                UNWIND $triples AS t
                MERGE (s:Entity {name: t.subject, kbId: $kbId})
                MERGE (o:Entity {name: t.object, kbId: $kbId})
                MERGE (s)-[r:RELATES {type: t.relation, kbId: $kbId}]->(o)
                SET r.docId = t.docId
                """;
        List<Map<String, Object>> tripleList = triples.stream()
                .map(t -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("subject", t.subject());
                    map.put("relation", t.relation());
                    map.put("object", t.object());
                    map.put("docId", t.docId());
                    return map;
                })
                .toList();
        try (Session session = neo4jDriver.session()) {
            session.run(cypher, Values.parameters("kbId", kbId, "triples", tripleList));
            log.debug("写入 {} 条三元组到图谱，kbId={}, docId={}", triples.size(), kbId, docId);
        }
    }

    /**
     * 删除文档对应的所有边
     * 同时清理孤立节点
     */
    public void deleteByDocId(String kbId, String docId) {
        String deleteCypher = """
                MATCH (s:Entity {kbId: $kbId})-[r:RELATES {docId: $docId, kbId: $kbId}]->(o:Entity {kbId: $kbId})
                DELETE r
                """;
        String cleanupCypher = """
                MATCH (e:Entity {kbId: $kbId})
                WHERE NOT (e)--()
                DELETE e
                """;
        try (Session session = neo4jDriver.session()) {
            session.run(deleteCypher, Values.parameters("kbId", kbId, "docId", docId));
            session.run(cleanupCypher, Values.parameters("kbId", kbId));
            log.debug("已清理文档图谱数据，kbId={}, docId={}", kbId, docId);
        }
    }

    /**
     * 基于实体名称进行子图遍历
     *
     * @param kbId     知识库 ID
     * @param entities 实体名称列表
     * @param maxHops  最大跳数
     * @param maxNodes 最大返回节点数
     * @return 匹配到的三元组列表
     */
    public List<GraphTriple> traverseByEntities(String kbId, List<String> entities, int maxHops, int maxNodes) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        String cypher = """
                UNWIND $entities AS entityName
                MATCH (start:Entity {kbId: $kbId})
                WHERE start.name CONTAINS entityName
                CALL apoc.path.subgraphAll(start, {maxLevel: $maxHops, limit: $maxNodes})
                YIELD relationships
                UNWIND relationships AS r
                WITH DISTINCT r
                RETURN startNode(r).name AS subject, r.type AS relation, endNode(r).name AS object, r.docId AS docId
                LIMIT $maxNodes
                """;
        // 使用兼容性更好的纯 Cypher 查询（无需 APOC）
        String fallbackCypher = """
                UNWIND $entities AS entityName
                MATCH (start:Entity {kbId: $kbId})
                WHERE start.name CONTAINS entityName
                MATCH path = (start)-[r:RELATES*1..%d {kbId: $kbId}]-(end:Entity {kbId: $kbId})
                UNWIND relationships(path) AS rel
                WITH DISTINCT rel
                RETURN startNode(rel).name AS subject, rel.type AS relation, endNode(rel).name AS object, rel.docId AS docId
                LIMIT $maxNodes
                """.formatted(maxHops);
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(fallbackCypher,
                    Values.parameters("kbId", kbId, "entities", entities, "maxNodes", maxNodes));
            List<GraphTriple> triples = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                triples.add(new GraphTriple(
                        record.get("subject").asString(""),
                        record.get("relation").asString(""),
                        record.get("object").asString(""),
                        record.get("docId").asString("")
                ));
            }
            log.debug("图谱遍历完成，kbId={}, 实体数={}, 结果数={}", kbId, entities.size(), triples.size());
            return triples;
        } catch (Exception e) {
            log.error("图谱遍历失败，kbId={}", kbId, e);
            return List.of();
        }
    }
}
