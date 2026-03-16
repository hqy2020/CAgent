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

import com.google.gson.JsonObject;
import com.openingcloud.ai.ragent.infra.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryVectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final UserMemoryProperties properties;

    private static final String FIELD_MEMORY_ID = "memory_id";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_EMBEDDING = "embedding";

    @PostConstruct
    public void init() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return;
        }
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("初始化记忆向量 collection 失败，将在首次使用时重试: {}", e.getMessage());
        }
    }

    public void upsert(Long memoryId, Long userId, String content) {
        try {
            ensureCollection();
            List<Float> embedding = embeddingService.embed(content);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("记忆嵌入失败，跳过向量入库: memoryId={}", memoryId);
                return;
            }

            JsonObject row = new JsonObject();
            row.addProperty(FIELD_MEMORY_ID, String.valueOf(memoryId));
            row.addProperty(FIELD_USER_ID, String.valueOf(userId));
            row.addProperty(FIELD_CONTENT, content);
            com.google.gson.JsonArray embArr = new com.google.gson.JsonArray();
            for (Float f : embedding) {
                embArr.add(f);
            }
            row.add(FIELD_EMBEDDING, embArr);

            milvusClient.insert(InsertReq.builder()
                    .collectionName(properties.getMilvusCollectionName())
                    .data(List.of(row))
                    .build());
        } catch (Exception e) {
            log.error("记忆向量入库失败: memoryId={}", memoryId, e);
        }
    }

    public void delete(Long memoryId) {
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(properties.getMilvusCollectionName())
                    .ids(List.of(String.valueOf(memoryId)))
                    .build());
        } catch (Exception e) {
            log.error("删除记忆向量失败: memoryId={}", memoryId, e);
        }
    }

    public List<VectorSearchResult> search(Long userId, String query, int topK) {
        try {
            List<Float> queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                return Collections.emptyList();
            }

            SearchResp resp = milvusClient.search(SearchReq.builder()
                    .collectionName(properties.getMilvusCollectionName())
                    .data(List.of(new FloatVec(queryEmbedding)))
                    .topK(topK)
                    .filter("user_id == \"" + userId + "\"")
                    .outputFields(List.of(FIELD_MEMORY_ID, FIELD_CONTENT))
                    .build());

            List<VectorSearchResult> results = new ArrayList<>();
            if (resp.getSearchResults() != null && !resp.getSearchResults().isEmpty()) {
                for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
                    for (SearchResp.SearchResult result : resultList) {
                        Map<String, Object> entity = result.getEntity();
                        String memIdStr = entity.get(FIELD_MEMORY_ID) != null
                                ? entity.get(FIELD_MEMORY_ID).toString() : null;
                        String content = entity.get(FIELD_CONTENT) != null
                                ? entity.get(FIELD_CONTENT).toString() : null;
                        results.add(new VectorSearchResult(
                                memIdStr != null ? Long.valueOf(memIdStr) : null,
                                content,
                                result.getScore()
                        ));
                    }
                }
            }
            return results;
        } catch (Exception e) {
            log.error("记忆向量搜索失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    private void ensureCollection() {
        String collectionName = properties.getMilvusCollectionName();
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (exists) {
            return;
        }

        int dimension = 4096; // 与 KB collection 一致

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_MEMORY_ID).dataType(DataType.VarChar).isPrimaryKey(true).maxLength(32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_USER_ID).dataType(DataType.VarChar).maxLength(32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT).dataType(DataType.VarChar).maxLength(4096).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_EMBEDDING).dataType(DataType.FloatVector).dimension(dimension).build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .build());

        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(IndexParam.builder()
                        .fieldName(FIELD_EMBEDDING)
                        .indexType(IndexParam.IndexType.HNSW)
                        .metricType(IndexParam.MetricType.COSINE)
                        .extraParams(Map.of("M", 16, "efConstruction", 200))
                        .build()))
                .build());

        log.info("创建记忆向量 collection: {}", collectionName);
    }

    public record VectorSearchResult(Long memoryId, String content, float score) {
    }
}
