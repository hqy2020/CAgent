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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    /**
     * 后处理器配置
     */
    private PostProcessor postProcessor = new PostProcessor();

    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * TopK 倍数
         * 全局检索时召回更多候选，后续通过 Rerank 筛选
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class PostProcessor {

        /**
         * RRF 融合配置
         */
        private RrfFusion rrfFusion = new RrfFusion();

        /**
         * 质量过滤配置
         */
        private QualityFilter qualityFilter = new QualityFilter();
    }

    @Data
    public static class RrfFusion {

        /**
         * 是否启用 RRF 融合
         */
        private boolean enabled = true;

        /**
         * RRF 平滑参数 k
         * 公式：RRF_score(d) = Σ 1/(k + rank_i)
         * 推荐值 60（来自 Cormack et al. 论文）
         */
        private int k = 60;
    }

    @Data
    public static class QualityFilter {

        /**
         * 是否启用质量过滤
         */
        private boolean enabled = true;

        /**
         * Rerank 分数阈值，低于此值视为相关性不足
         */
        private float scoreThreshold = 0.3f;

        /**
         * 最小内容长度（字符），低于此值视为信息量不足
         */
        private int minContentLength = 10;
    }
}
