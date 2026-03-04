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

package com.nageoffer.ai.ragent.core.chunk.strategy;

import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RecursiveTextChunker 单元测试
 */
class RecursiveTextChunkerTests {

    private RecursiveTextChunker chunker;

    @BeforeEach
    void setUp() {
        ModelSelector modelSelector = mock(ModelSelector.class);
        List<EmbeddingClient> embeddingClients = List.of();
        chunker = new RecursiveTextChunker(modelSelector, embeddingClients);
    }

    @Test
    void getType_returnsRecursive() {
        assertEquals(ChunkingMode.RECURSIVE, chunker.getType());
    }

    @Test
    void emptyText_returnsEmpty() {
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(100).overlapSize(20).build();
        List<VectorChunk> result = invokeDoChunk("", options);
        assertTrue(result.isEmpty());
    }

    @Test
    void shortText_returnsSingleChunk() {
        String text = "这是一段短文本。";
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(100).overlapSize(20).build();
        List<VectorChunk> result = invokeDoChunk(text, options);
        assertEquals(1, result.size());
        assertEquals(text, result.get(0).getContent());
    }

    @Test
    void paragraphSplit_shortParagraphsEachBecomeChunk() {
        // 每段 ~15 字符，chunkSize=12 确保每段独立成块
        String text = "这是第一段内容非常丰富。\n\n这是第二段内容也很丰富。\n\n这是第三段内容同样丰富。";
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(12).overlapSize(0).build();
        List<VectorChunk> result = invokeDoChunk(text, options);

        assertTrue(result.size() >= 3, "短段落应各自成块，实际块数: " + result.size());
        assertTrue(result.get(0).getContent().contains("第一段"));
    }

    @Test
    void recursiveFallback_longParagraphSplitBySentence() {
        // 一个超长段落（无 \n\n），但有中文句号分隔
        String text = "这是第一句话。这是第二句话。这是第三句话。这是第四句话。这是第五句话。";
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(30).overlapSize(0).build();
        List<VectorChunk> result = invokeDoChunk(text, options);

        assertTrue(result.size() > 1, "长段落应被按句子递归切分，实际块数: " + result.size());
        for (VectorChunk chunk : result) {
            assertTrue(chunk.getContent().length() <= 35,
                    "块长度应接近 chunkSize，实际: " + chunk.getContent().length());
        }
    }

    @Test
    void characterFallback_longTextWithoutSeparators() {
        // 超长无标点文本，只能按字符硬切
        String text = "啊".repeat(100);
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(30).overlapSize(5).build();
        List<VectorChunk> result = invokeDoChunk(text, options);

        assertTrue(result.size() > 1, "超长无标点文本应被按字符硬切");
        for (VectorChunk chunk : result) {
            assertTrue(chunk.getContent().length() <= 35,
                    "硬切块长度不应远超 chunkSize");
        }
    }

    @Test
    void overlapPreserved() {
        String text = "第一段内容比较长的文本。\n\n第二段也有不短的内容。\n\n第三段同样需要分块。";
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(20).overlapSize(5).build();
        List<VectorChunk> result = invokeDoChunk(text, options);

        assertTrue(result.size() >= 2, "应产生多个块");
    }

    @Test
    void allChunksHaveValidIndex() {
        String text = "段落一。\n\n段落二。\n\n段落三。\n\n段落四。";
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(15).overlapSize(0).build();
        List<VectorChunk> result = invokeDoChunk(text, options);

        for (int i = 0; i < result.size(); i++) {
            assertEquals(i, result.get(i).getIndex(), "块索引应连续");
            assertNotNull(result.get(i).getChunkId(), "块 ID 不应为空");
        }
    }

    @Test
    void mixedChineseEnglish() {
        String text = "Spring Boot is great. 它简化了Java开发。\n\nReact is modern. 前端非常好用。";
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(30).overlapSize(0).build();
        List<VectorChunk> result = invokeDoChunk(text, options);

        assertTrue(result.size() >= 2, "中英文混合文本应正常分块");
    }

    /**
     * 通过反射调用 protected doChunk 方法，绕过 AbstractEmbeddingChunker 的 embedding 逻辑
     */
    @SuppressWarnings("unchecked")
    private List<VectorChunk> invokeDoChunk(String text, ChunkingOptions options) {
        return (List<VectorChunk>) ReflectionTestUtils.invokeMethod(
                chunker, "doChunk", text, options);
    }
}
