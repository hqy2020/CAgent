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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.core.chunk.ChunkingMode;
import com.openingcloud.ai.ragent.core.chunk.ChunkingOptions;
import com.openingcloud.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.openingcloud.ai.ragent.core.chunk.VectorChunk;
import com.openingcloud.ai.ragent.core.chunk.ChunkingStrategy;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.ingestion.domain.context.IngestionContext;
import com.openingcloud.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.openingcloud.ai.ragent.ingestion.domain.result.NodeResult;
import com.openingcloud.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文本分块节点
 * 负责将输入的完整文本（原始文本或增强后的文本）按照指定的策略切分成多个较小的文本块（Chunk）
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail(new ClientException("可分块文本为空"));
        }
        ChunkerSettings settings = parseSettings(config.getSettings());
        ChunkingStrategy chunker = chunkingStrategyFactory.requireStrategy(settings.getStrategy());
        if (chunker == null) {
            return NodeResult.fail(new ClientException("未找到分块策略: " + settings.getStrategy()));
        }

        ChunkingOptions chunkConfig = convertToChunkConfig(settings);
        List<VectorChunk> results = chunker.chunk(text, chunkConfig);
        List<VectorChunk> chunks = convertToVectorChunks(results);

        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        Map<String, Object> metadata = new HashMap<>();
        if (settings.getTargetChars() != null) {
            metadata.put("targetChars", settings.getTargetChars());
        }
        if (settings.getMaxChars() != null) {
            metadata.put("maxChars", settings.getMaxChars());
        }
        if (settings.getMinChars() != null) {
            metadata.put("minChars", settings.getMinChars());
        }
        if (settings.getOverlapChars() != null) {
            metadata.put("overlapChars", settings.getOverlapChars());
        }
        return ChunkingOptions.builder()
                .chunkSize(settings.getTargetChars() != null ? settings.getTargetChars() : settings.getChunkSize())
                .overlapSize(settings.getOverlapChars() != null ? settings.getOverlapChars() : settings.getOverlapSize())
                .separator(settings.getSeparator())
                .metadata(metadata)
                .build();
    }

    private List<VectorChunk> convertToVectorChunks(List<VectorChunk> results) {
        return results.stream()
                .map(result -> VectorChunk.builder()
                        .chunkId(result.getChunkId())
                        .index(result.getIndex())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .embedding(result.getEmbedding())
                        .build())
                .collect(Collectors.toList());
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings;
        if (node == null || node.isNull()) {
            settings = ChunkerSettings.builder().build();
        } else {
            settings = objectMapper.convertValue(node, ChunkerSettings.class);
        }
        if (settings == null) {
            settings = ChunkerSettings.builder().build();
        }
        if (settings.getStrategy() == null) {
            settings.setStrategy(ChunkingMode.FIXED_SIZE);
        }
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(500);
        }
        if (settings.getStrategy() == ChunkingMode.STRUCTURE_AWARE) {
            if (settings.getTargetChars() == null || settings.getTargetChars() <= 0) {
                settings.setTargetChars(800);
            }
            if (settings.getMaxChars() == null || settings.getMaxChars() <= 0) {
                settings.setMaxChars(1000);
            }
            if (settings.getMinChars() == null || settings.getMinChars() <= 0) {
                settings.setMinChars(300);
            }
            if (settings.getOverlapChars() == null || settings.getOverlapChars() < 0) {
                settings.setOverlapChars(120);
            }
            return settings;
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(settings.getStrategy() == ChunkingMode.FIXED_SIZE ? 0 : 50);
        }
        return settings;
    }
}
