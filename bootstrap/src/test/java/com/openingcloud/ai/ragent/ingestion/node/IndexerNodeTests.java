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

package com.openingcloud.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.openingcloud.ai.ragent.core.chunk.VectorChunk;
import com.openingcloud.ai.ragent.ingestion.domain.context.IngestionContext;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.openingcloud.ai.ragent.ingestion.domain.result.NodeResult;
import com.openingcloud.ai.ragent.infra.embedding.EmbeddingService;
import com.openingcloud.ai.ragent.rag.config.RAGDefaultProperties;
import com.openingcloud.ai.ragent.rag.core.vector.VectorSpaceId;
import com.openingcloud.ai.ragent.rag.core.vector.VectorStoreAdmin;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexerNodeTests {

    @Mock
    private VectorStoreAdmin vectorStoreAdmin;
    @Mock
    private MilvusClientV2 milvusClient;
    @Mock
    private EmbeddingService embeddingService;

    @Test
    void shouldWriteKnowledgeDocumentIdentifiersToMilvusRows() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setCollectionName("default_collection");
        properties.setDimension(2);
        IndexerNode node = new IndexerNode(new ObjectMapper(), vectorStoreAdmin, milvusClient, properties, embeddingService);

        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(true);
        InsertResp response = mock(InsertResp.class);
        when(response.getInsertCnt()).thenReturn(1L);
        when(milvusClient.insert(any())).thenReturn(response);

        IngestionContext context = IngestionContext.builder()
                .taskId("task-1")
                .pipelineId("pipeline-1")
                .metadata(Map.of(
                        "kbId", "2028771736429842432",
                        "docId", "2031669455582887936"
                ))
                .vectorSpaceId(VectorSpaceId.builder().logicalName("kb_collection").build())
                .chunks(List.of(
                        VectorChunk.builder()
                                .chunkId("chunk-1")
                                .index(0)
                                .content("hello")
                                .embedding(new float[]{0.1f, 0.2f})
                                .build()
                ))
                .build();

        NodeConfig config = NodeConfig.builder()
                .nodeId("indexer-1")
                .nodeType("indexer")
                .build();

        NodeResult result = node.execute(context, config);

        assertTrue(result.isSuccess());
        ArgumentCaptor<InsertReq> requestCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient).insert(requestCaptor.capture());

        InsertReq request = requestCaptor.getValue();
        assertEquals("kb_collection", request.getCollectionName());
        JsonObject row = (JsonObject) request.getData().get(0);
        assertEquals("chunk-1", row.get("doc_id").getAsString());
        assertEquals("2028771736429842432", row.get("kb_id").getAsString());

        JsonObject metadata = row.getAsJsonObject("metadata");
        assertEquals("2028771736429842432", metadata.get("kb_id").getAsString());
        assertEquals("2031669455582887936", metadata.get("doc_id").getAsString());
        assertEquals(0, metadata.get("chunk_index").getAsInt());
    }

    @Test
    void shouldFallbackToTaskIdentifiersWhenKbMetadataMissing() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setCollectionName("default_collection");
        properties.setDimension(2);
        IndexerNode node = new IndexerNode(new ObjectMapper(), vectorStoreAdmin, milvusClient, properties, embeddingService);

        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(true);
        InsertResp response = mock(InsertResp.class);
        when(response.getInsertCnt()).thenReturn(1L);
        when(milvusClient.insert(any())).thenReturn(response);

        IngestionContext context = IngestionContext.builder()
                .taskId("task-2")
                .pipelineId("pipeline-2")
                .vectorSpaceId(VectorSpaceId.builder().logicalName("generic_collection").build())
                .chunks(List.of(
                        VectorChunk.builder()
                                .chunkId("chunk-2")
                                .index(1)
                                .content("world")
                                .embedding(new float[]{0.3f, 0.4f})
                                .build()
                ))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder()
                .nodeId("indexer-1")
                .nodeType("indexer")
                .build());

        assertTrue(result.isSuccess());
        ArgumentCaptor<InsertReq> requestCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient).insert(requestCaptor.capture());

        JsonObject row = (JsonObject) requestCaptor.getValue().getData().get(0);
        assertEquals("task-2", row.get("kb_id").getAsString());
        JsonObject metadata = row.getAsJsonObject("metadata");
        assertEquals("task-2", metadata.get("kb_id").getAsString());
        assertEquals("task-2", metadata.get("doc_id").getAsString());
    }
}
