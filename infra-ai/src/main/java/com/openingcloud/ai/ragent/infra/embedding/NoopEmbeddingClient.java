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

package com.openingcloud.ai.ragent.infra.embedding;

import com.openingcloud.ai.ragent.infra.enums.ModelProvider;
import com.openingcloud.ai.ragent.infra.model.ModelTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地无外部依赖的占位 EmbeddingClient
 * 生成稳定的伪向量，满足向量维度与入库链路校验
 */
@Component
public class NoopEmbeddingClient implements EmbeddingClient {

    private static final int DEFAULT_DIMENSION = 4096;

    @Override
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return toVector(text, resolveDimension(target));
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int dimension = resolveDimension(target);
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(toVector(text, dimension));
        }
        return vectors;
    }

    private int resolveDimension(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getDimension() == null) {
            return DEFAULT_DIMENSION;
        }
        Integer dim = target.candidate().getDimension();
        return dim > 0 ? dim : DEFAULT_DIMENSION;
    }

    private List<Float> toVector(String text, int dimension) {
        String source = text == null ? "" : text;
        long seed = fnv1a64(source);
        List<Float> vector = new ArrayList<>(dimension);
        for (int i = 0; i < dimension; i++) {
            // xorshift64*，保证同输入稳定输出
            seed ^= (seed << 13);
            seed ^= (seed >>> 7);
            seed ^= (seed << 17);
            float value = ((seed & 0x00FFFFFFL) / 8388607.0f) - 1.0f;
            vector.add(value);
        }
        return vector;
    }

    private long fnv1a64(String source) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < source.length(); i++) {
            hash ^= source.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
