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

package com.openingcloud.ai.ragent.rag.retrieve;

import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.openingcloud.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.openingcloud.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.openingcloud.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.openingcloud.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.openingcloud.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MultiChannelRetrievalEngineTests {

    private MultiChannelRetrievalEngine engine;
    private SearchChannel vectorChannel;
    private SearchChannel intentChannel;
    private SearchResultPostProcessor dedup;
    private SearchResultPostProcessor rerank;
    private final Executor syncExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        vectorChannel = mock(SearchChannel.class);
        intentChannel = mock(SearchChannel.class);
        dedup = mock(SearchResultPostProcessor.class);
        rerank = mock(SearchResultPostProcessor.class);

        when(vectorChannel.getName()).thenReturn("VectorGlobal");
        when(vectorChannel.getPriority()).thenReturn(10);
        when(vectorChannel.getType()).thenReturn(SearchChannelType.VECTOR_GLOBAL);

        when(intentChannel.getName()).thenReturn("IntentDirected");
        when(intentChannel.getPriority()).thenReturn(1);
        when(intentChannel.getType()).thenReturn(SearchChannelType.INTENT_DIRECTED);

        when(dedup.getName()).thenReturn("Dedup");
        when(dedup.getOrder()).thenReturn(1);

        when(rerank.getName()).thenReturn("Rerank");
        when(rerank.getOrder()).thenReturn(10);

        engine = new MultiChannelRetrievalEngine(
                List.of(vectorChannel, intentChannel),
                List.of(rerank, dedup),
                syncExecutor
        );
    }

    @Test
    void testTwoChannelsParallelExecution() {
        when(vectorChannel.isEnabled(any())).thenReturn(true);
        when(intentChannel.isEnabled(any())).thenReturn(true);

        RetrievedChunk chunk1 = RetrievedChunk.builder().id("1").text("A").score(0.9f).build();
        RetrievedChunk chunk2 = RetrievedChunk.builder().id("2").text("B").score(0.8f).build();

        when(vectorChannel.search(any())).thenReturn(SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL).channelName("VectorGlobal")
                .chunks(List.of(chunk1)).confidence(0.9).build());
        when(intentChannel.search(any())).thenReturn(SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED).channelName("IntentDirected")
                .chunks(List.of(chunk2)).confidence(0.95).build());

        when(dedup.isEnabled(any())).thenReturn(true);
        when(rerank.isEnabled(any())).thenReturn(true);
        when(dedup.process(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(rerank.process(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<SubQuestionIntent> intents = List.of(
                new SubQuestionIntent("test question", List.of())
        );
        List<RetrievedChunk> result = engine.retrieveKnowledgeChannels(intents, 5);

        assertEquals(2, result.size());
        verify(vectorChannel).search(any());
        verify(intentChannel).search(any());
    }

    @Test
    void testPostProcessorChainOrder() {
        when(vectorChannel.isEnabled(any())).thenReturn(true);
        when(intentChannel.isEnabled(any())).thenReturn(false);

        RetrievedChunk chunk = RetrievedChunk.builder().id("1").text("A").score(0.9f).build();
        when(vectorChannel.search(any())).thenReturn(SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL).channelName("VectorGlobal")
                .chunks(List.of(chunk)).confidence(0.9).build());

        when(dedup.isEnabled(any())).thenReturn(true);
        when(rerank.isEnabled(any())).thenReturn(true);

        List<String> executionOrder = new ArrayList<>();
        when(dedup.process(any(), any(), any())).thenAnswer(inv -> {
            executionOrder.add("dedup");
            return inv.getArgument(0);
        });
        when(rerank.process(any(), any(), any())).thenAnswer(inv -> {
            executionOrder.add("rerank");
            return inv.getArgument(0);
        });

        engine.retrieveKnowledgeChannels(
                List.of(new SubQuestionIntent("q", List.of())), 5);

        assertEquals(List.of("dedup", "rerank"), executionOrder);
    }

    @Test
    void testChannelExceptionDoesNotAffectOthers() {
        when(vectorChannel.isEnabled(any())).thenReturn(true);
        when(intentChannel.isEnabled(any())).thenReturn(true);

        when(vectorChannel.search(any())).thenThrow(new RuntimeException("vector error"));

        RetrievedChunk chunk = RetrievedChunk.builder().id("2").text("B").score(0.8f).build();
        when(intentChannel.search(any())).thenReturn(SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED).channelName("IntentDirected")
                .chunks(List.of(chunk)).confidence(0.95).build());

        when(dedup.isEnabled(any())).thenReturn(true);
        when(rerank.isEnabled(any())).thenReturn(false);
        when(dedup.process(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<RetrievedChunk> result = engine.retrieveKnowledgeChannels(
                List.of(new SubQuestionIntent("q", List.of())), 5);

        assertFalse(result.isEmpty());
    }

    @Test
    void testEmptyChannelsReturnEmptyList() {
        when(vectorChannel.isEnabled(any())).thenReturn(false);
        when(intentChannel.isEnabled(any())).thenReturn(false);

        List<RetrievedChunk> result = engine.retrieveKnowledgeChannels(
                List.of(new SubQuestionIntent("q", List.of())), 5);

        assertTrue(result.isEmpty());
        verify(vectorChannel, never()).search(any());
        verify(intentChannel, never()).search(any());
    }
}
