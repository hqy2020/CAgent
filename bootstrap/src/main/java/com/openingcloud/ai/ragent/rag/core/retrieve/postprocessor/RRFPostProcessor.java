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

package com.openingcloud.ai.ragent.rag.core.retrieve.postprocessor;

import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.rag.config.SearchChannelProperties;
import com.openingcloud.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.openingcloud.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RRF (Reciprocal Rank Fusion) 后置处理器
 * <p>
 * 在去重之后、Rerank 之前执行，对多通道检索结果进行排名融合。
 * 公式：RRF_score(d) = Sigma 1/(k + rank_i)，rank 从 1 开始。
 * 融合后归一化到 [0, 1] 范围并写入 chunk.score。
 * <p>
 * 单通道场景自动跳过（退化为保持原序）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RRFPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties searchChannelProperties;

    @Override
    public String getName() {
        return "RRFusion";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return searchChannelProperties.getPostProcessor().getRrfFusion().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 过滤出有效通道（包含 chunk 的通道）
        List<SearchChannelResult> effectiveChannels = results.stream()
                .filter(r -> r.getChunks() != null && !r.getChunks().isEmpty())
                .toList();

        if (effectiveChannels.size() < 2) {
            log.debug("有效通道数 < 2，跳过 RRF 融合");
            return chunks;
        }

        int k = searchChannelProperties.getPostProcessor().getRrfFusion().getK();

        // 按 chunkKey 聚合各通道的 RRF 分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, RetrievedChunk> chunkIndex = new LinkedHashMap<>();

        for (SearchChannelResult channelResult : effectiveChannels) {
            List<RetrievedChunk> channelChunks = channelResult.getChunks();
            for (int rank = 0; rank < channelChunks.size(); rank++) {
                RetrievedChunk chunk = channelChunks.get(rank);
                String key = generateChunkKey(chunk);
                double rrfContribution = 1.0 / (k + rank + 1); // rank 从 1 开始
                rrfScores.merge(key, rrfContribution, Double::sum);
                chunkIndex.putIfAbsent(key, chunk);
            }
        }

        if (rrfScores.isEmpty()) {
            return chunks;
        }

        // 找到最大 RRF 分数，用于归一化
        double maxScore = rrfScores.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1.0);

        // 归一化到 [0, 1] 并按分数降序排序
        List<RetrievedChunk> fused = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    RetrievedChunk chunk = chunkIndex.get(entry.getKey());
                    float normalizedScore = (float) (entry.getValue() / maxScore);
                    chunk.setScore(normalizedScore);
                    return chunk;
                })
                .toList();

        log.info("RRF 融合完成：{} 个通道, {} -> {} 条结果 (k={})",
                effectiveChannels.size(), chunks.size(), fused.size(), k);
        return fused;
    }

    /**
     * 生成 Chunk 唯一键（与 DeduplicationPostProcessor 同款逻辑）
     */
    private String generateChunkKey(RetrievedChunk chunk) {
        if (chunk.getId() != null) {
            return chunk.getId();
        }
        String text = chunk.getText();
        return text != null
                ? String.valueOf(text.hashCode())
                : String.valueOf(System.identityHashCode(chunk));
    }
}
