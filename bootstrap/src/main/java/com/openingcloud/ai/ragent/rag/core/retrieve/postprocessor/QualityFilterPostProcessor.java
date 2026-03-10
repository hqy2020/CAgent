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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 质量过滤后置处理器
 * <p>
 * 在 Rerank 之后执行，过滤低质量 Chunk：
 * 1. 分数低于阈值的（相关性不足）
 * 2. 内容长度不足的（信息量不足）
 * 全部被过滤时根据 keepBestWhenAllFiltered 配置决定是保留最高分兜底还是返回空列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityFilterPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties searchChannelProperties;

    @Override
    public String getName() {
        return "QualityFilter";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return searchChannelProperties.getPostProcessor().getQualityFilter().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过质量过滤");
            return chunks;
        }

        SearchChannelProperties.QualityFilter config = searchChannelProperties.getPostProcessor().getQualityFilter();
        float scoreThreshold = config.getScoreThreshold();
        int minContentLength = config.getMinContentLength();
        int originalSize = chunks.size();

        List<RetrievedChunk> filtered = chunks.stream()
                .filter(chunk -> {
                    Float score = chunk.getScore();
                    if (score != null && score < scoreThreshold) {
                        return false;
                    }
                    String text = chunk.getText();
                    return text != null && text.trim().length() >= minContentLength;
                })
                .toList();

        if (filtered.isEmpty()) {
            if (config.isKeepBestWhenAllFiltered()) {
                RetrievedChunk best = chunks.stream()
                        .max(Comparator.comparing(c -> c.getScore() != null ? c.getScore() : Float.MIN_VALUE))
                        .get();
                log.warn("质量过滤后全部被淘汰，兜底保留分数最高的 Chunk (score={})", best.getScore());
                return List.of(best);
            }
            log.warn("质量过滤后全部被淘汰，返回空列表（最高分: {}）",
                    chunks.stream().map(RetrievedChunk::getScore).filter(Objects::nonNull)
                            .max(Float::compareTo).orElse(null));
            return List.of();
        }

        log.info("质量过滤完成：{} -> {} 条（阈值: score >= {}, 长度 >= {}）",
                originalSize, filtered.size(), scoreThreshold, minContentLength);
        return filtered;
    }
}
