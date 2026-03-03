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

package com.nageoffer.ai.ragent.rag.retrieve;

import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.RRFPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RRFPostProcessor 单元测试
 */
class RRFPostProcessorTests {

    private SearchChannelProperties properties;
    private RRFPostProcessor processor;
    private SearchContext context;

    @BeforeEach
    void setUp() {
        properties = new SearchChannelProperties();
        properties.getPostProcessor().getRrfFusion().setEnabled(true);
        properties.getPostProcessor().getRrfFusion().setK(60);
        processor = new RRFPostProcessor(properties);
        context = SearchContext.builder()
                .originalQuestion("测试问题")
                .topK(10)
                .build();
    }

    @Test
    void testSingleChannelSkipsRRF() {
        // 单通道场景应直接返回原列表
        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").text("chunk1").score(0.9f).build(),
                RetrievedChunk.builder().id("c2").text("chunk2").score(0.8f).build()
        );

        SearchChannelResult channel1 = SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName("intent")
                .chunks(chunks)
                .confidence(0.9)
                .latencyMs(50)
                .build();

        List<RetrievedChunk> result = processor.process(chunks, List.of(channel1), context);
        assertSame(chunks, result);
    }

    @Test
    void testMultiChannelFusion() {
        // 两个通道共享部分 chunk，验证 RRF 融合后排序
        RetrievedChunk c1 = RetrievedChunk.builder().id("c1").text("chunk1").score(0.9f).build();
        RetrievedChunk c2 = RetrievedChunk.builder().id("c2").text("chunk2").score(0.8f).build();
        RetrievedChunk c3 = RetrievedChunk.builder().id("c3").text("chunk3").score(0.7f).build();
        RetrievedChunk c1Dup = RetrievedChunk.builder().id("c1").text("chunk1").score(0.5f).build();

        // 通道1: c1(rank1), c2(rank2), c3(rank3)
        SearchChannelResult channel1 = SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName("intent")
                .chunks(List.of(c1, c2, c3))
                .confidence(0.9)
                .latencyMs(50)
                .build();

        // 通道2: c1(rank1), c3 未出现 -> c1 在两个通道都排第一
        SearchChannelResult channel2 = SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL)
                .channelName("global")
                .chunks(List.of(c1Dup, c3))
                .confidence(0.7)
                .latencyMs(80)
                .build();

        // 合并后的 chunks（去重后）
        List<RetrievedChunk> mergedChunks = List.of(c1, c2, c3);

        List<RetrievedChunk> result = processor.process(
                mergedChunks, List.of(channel1, channel2), context);

        // c1 出现在两个通道的 rank1，RRF 最高
        assertEquals("c1", result.get(0).getId());

        // c3 出现在 channel1 rank3 和 channel2 rank2
        // c2 只出现在 channel1 rank2
        // c3 的 RRF: 1/(60+3) + 1/(60+2) = 1/63 + 1/62
        // c2 的 RRF: 1/(60+2) = 1/62
        // 所以 c3 > c2
        assertEquals("c3", result.get(1).getId());
        assertEquals("c2", result.get(2).getId());

        // 所有分数应在 [0, 1] 范围内
        for (RetrievedChunk chunk : result) {
            assertTrue(chunk.getScore() >= 0f && chunk.getScore() <= 1f,
                    "Score should be normalized to [0,1], got: " + chunk.getScore());
        }

        // 第一名的归一化分数应为 1.0
        assertEquals(1.0f, result.get(0).getScore(), 0.001f);
    }

    @Test
    void testDisabledConfig() {
        properties.getPostProcessor().getRrfFusion().setEnabled(false);
        assertFalse(processor.isEnabled(context));
    }

    @Test
    void testEnabledConfig() {
        assertTrue(processor.isEnabled(context));
    }

    @Test
    void testOrderIs5() {
        assertEquals(5, processor.getOrder());
    }

    @Test
    void testEmptyChannelResultsSkipped() {
        // 一个有效通道 + 一个空通道 => 有效通道数 < 2，跳过
        SearchChannelResult channel1 = SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .channelName("intent")
                .chunks(List.of(RetrievedChunk.builder().id("c1").text("a").score(0.9f).build()))
                .confidence(0.9)
                .latencyMs(50)
                .build();

        SearchChannelResult emptyChannel = SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR_GLOBAL)
                .channelName("global")
                .chunks(new ArrayList<>())
                .confidence(0.5)
                .latencyMs(30)
                .build();

        List<RetrievedChunk> chunks = List.of(
                RetrievedChunk.builder().id("c1").text("a").score(0.9f).build());

        List<RetrievedChunk> result = processor.process(
                chunks, List.of(channel1, emptyChannel), context);
        assertSame(chunks, result);
    }
}
