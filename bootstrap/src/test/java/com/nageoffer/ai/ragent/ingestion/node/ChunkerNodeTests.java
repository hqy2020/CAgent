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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkerNodeTests {

    @Mock
    private ChunkingStrategyFactory chunkingStrategyFactory;
    @Mock
    private ChunkingStrategy chunkingStrategy;

    @Test
    void testShouldUseDefaultSettingsWhenConfigIsNull() {
        ChunkerNode node = new ChunkerNode(new ObjectMapper(), chunkingStrategyFactory);
        when(chunkingStrategyFactory.requireStrategy(ChunkingMode.FIXED_SIZE)).thenReturn(chunkingStrategy);
        when(chunkingStrategy.chunk(any(), any())).thenReturn(List.of(
                VectorChunk.builder().chunkId("c1").index(0).content("hello").build()
        ));

        IngestionContext context = IngestionContext.builder()
                .rawText("hello world")
                .build();
        NodeConfig config = NodeConfig.builder()
                .nodeId("chunker-1")
                .nodeType("chunker")
                .build();

        NodeResult result = node.execute(context, config);

        assertTrue(result.isSuccess());
        assertEquals(1, context.getChunks().size());
        verify(chunkingStrategyFactory).requireStrategy(eq(ChunkingMode.FIXED_SIZE));
    }
}
