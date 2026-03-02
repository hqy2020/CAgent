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

package com.nageoffer.ai.ragent.ingestion;

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.engine.ConditionEvaluator;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.engine.NodeOutputExtractor;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IngestionPipelineTests {

    private IngestionNode createNode(String type, NodeResult result) {
        IngestionNode node = mock(IngestionNode.class);
        when(node.getNodeType()).thenReturn(type);
        when(node.execute(any(), any())).thenReturn(result);
        return node;
    }

    private IngestionEngine createEngine(List<IngestionNode> nodes) {
        ConditionEvaluator evaluator = mock(ConditionEvaluator.class);
        NodeOutputExtractor extractor = mock(NodeOutputExtractor.class);
        return new IngestionEngine(nodes, evaluator, extractor);
    }

    @Test
    void testFullPipelineExecution() {
        NodeResult success = NodeResult.builder().success(true).shouldContinue(true).message("ok").build();
        IngestionNode fetcher = createNode("fetcher", success);
        IngestionNode parser = createNode("parser", success);
        IngestionNode chunker = createNode("chunker", success);

        IngestionEngine engine = createEngine(List.of(fetcher, parser, chunker));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipe-1").name("Test Pipeline")
                .nodes(List.of(
                        NodeConfig.builder().nodeId("n1").nodeType("fetcher").nextNodeId("n2").build(),
                        NodeConfig.builder().nodeId("n2").nodeType("parser").nextNodeId("n3").build(),
                        NodeConfig.builder().nodeId("n3").nodeType("chunker").build()
                ))
                .build();

        IngestionContext ctx = IngestionContext.builder().taskId("t1").logs(new ArrayList<>()).build();
        IngestionContext result = engine.execute(pipeline, ctx);

        assertEquals(IngestionStatus.COMPLETED, result.getStatus());
        verify(fetcher).execute(any(), any());
        verify(parser).execute(any(), any());
        verify(chunker).execute(any(), any());
    }

    @Test
    void testNodeFailureStopsPipeline() {
        NodeResult success = NodeResult.builder().success(true).shouldContinue(true).message("ok").build();
        NodeResult failure = NodeResult.builder().success(false).shouldContinue(false)
                .message("parse error").error(new RuntimeException("bad format")).build();

        IngestionNode fetcher = createNode("fetcher", success);
        IngestionNode parser = createNode("parser", failure);
        IngestionNode chunker = createNode("chunker", success);

        IngestionEngine engine = createEngine(List.of(fetcher, parser, chunker));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipe-2").name("Fail Pipeline")
                .nodes(List.of(
                        NodeConfig.builder().nodeId("n1").nodeType("fetcher").nextNodeId("n2").build(),
                        NodeConfig.builder().nodeId("n2").nodeType("parser").nextNodeId("n3").build(),
                        NodeConfig.builder().nodeId("n3").nodeType("chunker").build()
                ))
                .build();

        IngestionContext ctx = IngestionContext.builder().taskId("t2").logs(new ArrayList<>()).build();
        IngestionContext result = engine.execute(pipeline, ctx);

        assertEquals(IngestionStatus.FAILED, result.getStatus());
        verify(fetcher).execute(any(), any());
        verify(parser).execute(any(), any());
        verify(chunker, never()).execute(any(), any());
    }

    @Test
    void testCyclicPipelineThrowsException() {
        IngestionNode fetcher = createNode("fetcher",
                NodeResult.builder().success(true).shouldContinue(true).build());

        IngestionEngine engine = createEngine(List.of(fetcher));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipe-3").name("Cyclic")
                .nodes(List.of(
                        NodeConfig.builder().nodeId("n1").nodeType("fetcher").nextNodeId("n2").build(),
                        NodeConfig.builder().nodeId("n2").nodeType("fetcher").nextNodeId("n1").build()
                ))
                .build();

        IngestionContext ctx = IngestionContext.builder().taskId("t3").logs(new ArrayList<>()).build();

        assertThrows(Exception.class, () -> engine.execute(pipeline, ctx));
    }

    @Test
    void testSingleNodePipeline() {
        NodeResult success = NodeResult.builder().success(true).shouldContinue(true).message("done").build();
        IngestionNode fetcher = createNode("fetcher", success);

        IngestionEngine engine = createEngine(List.of(fetcher));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipe-4").name("Single")
                .nodes(List.of(
                        NodeConfig.builder().nodeId("n1").nodeType("fetcher").build()
                ))
                .build();

        IngestionContext ctx = IngestionContext.builder().taskId("t4").logs(new ArrayList<>()).build();
        IngestionContext result = engine.execute(pipeline, ctx);

        assertEquals(IngestionStatus.COMPLETED, result.getStatus());
        assertEquals(1, result.getLogs().size());
    }
}
