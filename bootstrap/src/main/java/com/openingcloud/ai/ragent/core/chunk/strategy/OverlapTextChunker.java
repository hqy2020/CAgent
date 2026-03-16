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

package com.openingcloud.ai.ragent.core.chunk.strategy;

import com.openingcloud.ai.ragent.core.chunk.ChunkingMode;
import com.openingcloud.ai.ragent.infra.embedding.EmbeddingClient;
import com.openingcloud.ai.ragent.infra.model.ModelSelector;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重叠滑窗分块器
 * <p>
 * 与固定大小分块共享实现，只是在策略语义上强调 overlap 是主参数。
 */
@Component
public class OverlapTextChunker extends FixedSizeTextChunker {

    public OverlapTextChunker(ModelSelector modelSelector, List<EmbeddingClient> embeddingClients) {
        super(modelSelector, embeddingClients);
    }

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.OVERLAP;
    }
}
