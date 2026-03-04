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

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.core.chunk.AbstractEmbeddingChunker;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分块器
 * 核心思想：按分隔符从粗到细逐级递归切分，优先保留语义完整性
 * <p>
 * 默认分隔符列表（中英文混合场景，从粗到细）：
 * {@code \n\n → \n → 。 → ！ → ？ → . → ! → ? → ， → , → (空格) → (空串，按字符硬切)}
 * <p>
 * 算法流程：
 * 1. 从分隔符列表中找到文本中存在的最大分隔符
 * 2. 用该分隔符 split 文本
 * 3. 将分割片段累积合并，直到接近 chunkSize
 * 4. 如果某个片段仍超过 chunkSize，对该片段递归调用自身，使用下一级更细的分隔符
 * 5. 相邻块之间保留 overlapSize 重叠
 */
@Component
public class RecursiveTextChunker extends AbstractEmbeddingChunker {

    private static final List<String> DEFAULT_SEPARATORS = List.of(
            "\n\n", "\n",
            "。", "！", "？",
            ". ", "! ", "? ",
            "，", ", ",
            " ",
            ""
    );

    public RecursiveTextChunker(ModelSelector modelSelector, List<EmbeddingClient> embeddingClients) {
        super(modelSelector, embeddingClients);
    }

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.RECURSIVE;
    }

    @Override
    protected List<VectorChunk> doChunk(String text, ChunkingOptions config) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        int chunkSize = Math.max(1, config.getChunkSize());
        int overlapSize = Math.max(0, Math.min(config.getOverlapSize(), chunkSize - 1));

        @SuppressWarnings("unchecked")
        List<String> customSeparators = config.getMetadata("separators", null);
        List<String> separators = customSeparators != null ? customSeparators : DEFAULT_SEPARATORS;

        List<String> textChunks = splitRecursive(text, separators, 0, chunkSize, overlapSize);

        List<VectorChunk> result = new ArrayList<>(textChunks.size());
        for (int i = 0; i < textChunks.size(); i++) {
            result.add(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(i)
                    .content(textChunks.get(i))
                    .build());
        }
        return result;
    }

    /**
     * 递归切分核心方法
     *
     * @param text         待切分文本
     * @param separators   分隔符列表（从粗到细）
     * @param sepIndex     当前使用的分隔符在列表中的索引
     * @param chunkSize    块目标大小
     * @param overlapSize  重叠大小
     * @return 切分后的文本块列表
     */
    private List<String> splitRecursive(String text, List<String> separators, int sepIndex,
                                        int chunkSize, int overlapSize) {
        if (text.length() <= chunkSize) {
            return StringUtils.hasText(text) ? List.of(text) : List.of();
        }

        // 找到文本中存在的当前或下一级分隔符
        int effectiveSepIndex = sepIndex;
        while (effectiveSepIndex < separators.size() - 1) {
            String sep = separators.get(effectiveSepIndex);
            if (!sep.isEmpty() && text.contains(sep)) {
                break;
            }
            effectiveSepIndex++;
        }

        String separator = separators.get(effectiveSepIndex);
        int nextSepIndex = effectiveSepIndex + 1;

        // 空串分隔符 = 按字符硬切
        if (separator.isEmpty()) {
            return splitByCharacter(text, chunkSize, overlapSize);
        }

        String[] splits = text.split(escapeRegex(separator), -1);

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < splits.length; i++) {
            String piece = splits[i];
            // 还原分隔符到片段末尾（最后一个片段除外）
            String pieceWithSep = (i < splits.length - 1) ? piece + separator : piece;

            if (current.length() + pieceWithSep.length() <= chunkSize) {
                current.append(pieceWithSep);
            } else {
                // 先把已累积的内容输出
                if (current.length() > 0) {
                    addChunkWithOverlap(result, current.toString().strip(), overlapSize);
                    current.setLength(0);
                }

                // 如果单个片段仍超过 chunkSize，递归降级
                if (pieceWithSep.length() > chunkSize && nextSepIndex < separators.size()) {
                    List<String> subChunks = splitRecursive(pieceWithSep, separators, nextSepIndex,
                            chunkSize, overlapSize);
                    result.addAll(subChunks);
                } else {
                    current.append(pieceWithSep);
                }
            }
        }

        // 输出最后的累积内容
        if (current.length() > 0) {
            String last = current.toString().strip();
            if (StringUtils.hasText(last)) {
                addChunkWithOverlap(result, last, overlapSize);
            }
        }

        return result;
    }

    /**
     * 添加新块时，在前一个块末尾和新块开头之间保留 overlap 重叠
     */
    private void addChunkWithOverlap(List<String> chunks, String newChunk, int overlapSize) {
        if (!StringUtils.hasText(newChunk)) {
            return;
        }
        if (chunks.isEmpty() || overlapSize <= 0) {
            chunks.add(newChunk);
            return;
        }

        String prevChunk = chunks.get(chunks.size() - 1);
        int overlapLen = Math.min(overlapSize, prevChunk.length());
        String overlapText = prevChunk.substring(prevChunk.length() - overlapLen);

        // 仅当新块不以 overlap 文本开头时，才添加 overlap 前缀
        if (!newChunk.startsWith(overlapText)) {
            chunks.add(overlapText + newChunk);
        } else {
            chunks.add(newChunk);
        }
    }

    /**
     * 按字符硬切（兜底策略）
     */
    private List<String> splitByCharacter(String text, int chunkSize, int overlapSize) {
        List<String> result = new ArrayList<>();
        int len = text.length();
        int start = 0;

        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            String chunk = text.substring(start, end).strip();
            if (StringUtils.hasText(chunk)) {
                result.add(chunk);
            }
            if (end >= len) break;
            int nextStart = end - overlapSize;
            if (nextStart <= start) nextStart = end;
            start = nextStart;
        }

        return result;
    }

    private String escapeRegex(String separator) {
        return separator.replaceAll("([\\\\\\[\\]{}()*+?.^$|])", "\\\\$1");
    }
}
