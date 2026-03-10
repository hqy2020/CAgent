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

package com.openingcloud.ai.ragent.rag.core.vector;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.openingcloud.ai.ragent.core.chunk.VectorChunk;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStoreService implements VectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final KnowledgeBaseMapper kbMapper;

    @Override
    public void indexDocumentChunks(String kbId, String docId, List<VectorChunk> chunks) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        // 维度校验（你的 schema dim=4096）
        final int dim = 4096;
        List<float[]> vectors = extractVectors(chunks, dim);

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);

            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("kb_id", kbId);
            metadata.addProperty("doc_id", docId);
            metadata.addProperty("chunk_index", chunk.getIndex());

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunk.getChunkId());
            row.addProperty("kb_id", kbId);  // 添加独立字段用于过滤查询
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors.get(i)));

            rows.add(row);
        }

        String collection = kbDO.getCollectionName();
        InsertReq req = InsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus chunk 建立/写入向量索引成功, collection={}, rows={}", collection, resp.getInsertCnt());
    }

    @Override
    public void updateChunk(String kbId, String docId, VectorChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        // 维度校验
        final int dim = 4096;
        float[] vector = extractVector(chunk, dim);

        String chunkPk = chunk.getChunkId() != null ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();

        String content = chunk.getContent() == null ? "" : chunk.getContent();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("kb_id", kbId);
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.getIndex());

        JsonObject row = new JsonObject();
        row.addProperty("doc_id", chunkPk);
        row.addProperty("kb_id", kbId);  // 添加独立字段用于过滤查询
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        List<JsonObject> rows = List.of(row);

        String collection = kbDO.getCollectionName();

        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);

        log.info("Milvus 更新 chunk 向量索引成功, collection={}, kbId={}, docId={}, chunkId={}, upsertCnt={}",
                collection, kbId, docId, chunkPk, resp.getUpsertCnt());
    }

    private List<float[]> extractVectors(List<VectorChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            float[] vector = extractVector(chunk, expectedDim);
            vectors.add(vector);
        }
        return vectors;
    }

    private float[] extractVector(VectorChunk chunk, int expectedDim) {
        float[] vector = chunk.getEmbedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    @Override
    public void deleteDocumentVectors(String kbId, String docId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        String collection = kbDO.getCollectionName();

        // 尝试使用主键直接删除（docId 就是 chunk 的主键）
        // 这是最简单且兼容新旧 schema 的方式
        try {
            DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(collection)
                    .filter("doc_id == \"" + docId + "\"")
                    .build();
            DeleteResp deleteResp = milvusClient.delete(deleteReq);
            log.info("Milvus 删除文档向量索引成功, collection={}, kbId={}, docId={}, deleteCnt={}",
                    collection, kbId, docId, deleteResp.getDeleteCnt());
        } catch (Exception e) {
            // 如果主键删除失败，尝试查询后删除（兼容旧 schema）
            log.warn("主键删除失败，尝试查询删除: {}", e.getMessage());
            try {
                // 尝试使用 metadata 中的 kb_id 查询（旧 schema）
                QueryReq queryReq = QueryReq.builder()
                        .collectionName(collection)
                        .filter("metadata[\"doc_id\"] == \"" + docId + "\"")
                        .outputFields(List.of("doc_id"))
                        .build();

                QueryResp queryResp = milvusClient.query(queryReq);
                List<QueryResp.QueryResult> results = queryResp.getQueryResults();

                if (results == null || results.isEmpty()) {
                    log.info("Milvus 中没有找到需要删除的 chunk, collection={}, kbId={}, docId={}",
                            collection, kbId, docId);
                    return;
                }

                // 逐个通过主键删除
                int deleteCnt = 0;
                for (QueryResp.QueryResult result : results) {
                    String pk = (String) result.getEntity().get("doc_id");
                    DeleteReq deleteReq = DeleteReq.builder()
                            .collectionName(collection)
                            .filter("doc_id == \"" + pk + "\"")
                            .build();
                    DeleteResp deleteResp = milvusClient.delete(deleteReq);
                    deleteCnt += deleteResp.getDeleteCnt();
                }

                log.info("Milvus 删除指定文档的所有 chunk 向量索引成功(旧schema), collection={}, kbId={}, docId={}, deleteCnt={}",
                        collection, kbId, docId, deleteCnt);
            } catch (Exception ex) {
                log.error("删除向量数据失败, collection={}, docId={}", collection, docId, ex);
                throw new ClientException("删除向量数据失败: " + ex.getMessage());
            }
        }
    }


    @Override
    public void deleteChunkById(String kbId, String chunkId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        String collection = kbDO.getCollectionName();

        // Milvus 2.6 主键删除需要使用 pk 关键字
        String filter = "pk == \"" + chunkId + "\"";

        try {
            DeleteReq deleteReq = DeleteReq.builder()
                    .collectionName(collection)
                    .filter(filter)
                    .build();

            DeleteResp resp = milvusClient.delete(deleteReq);
            log.info("Milvus 删除指定 chunk 向量索引成功, collection={}, kbId={}, chunkId={}, deleteCnt={}",
                    collection, kbId, chunkId, resp.getDeleteCnt());
        } catch (Exception e) {
            log.error("删除 chunk 向量失败: {}", e.getMessage());
            // 如果失败，尝试使用主键字段名
            try {
                String fallbackFilter = "doc_id == \"" + chunkId + "\"";
                DeleteReq deleteReq = DeleteReq.builder()
                        .collectionName(collection)
                        .filter(fallbackFilter)
                        .build();
                DeleteResp resp = milvusClient.delete(deleteReq);
                log.info("Milvus 删除指定 chunk 向量索引成功(备用), collection={}, chunkId={}, deleteCnt={}",
                        collection, chunkId, resp.getDeleteCnt());
            } catch (Exception ex) {
                log.error("备用删除也失败: {}", ex.getMessage());
                throw new ClientException("删除向量数据失败: " + ex.getMessage());
            }
        }
    }

    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }
}
