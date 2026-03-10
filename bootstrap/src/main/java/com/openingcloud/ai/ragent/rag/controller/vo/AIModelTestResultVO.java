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

package com.openingcloud.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型连通性测试结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIModelTestResultVO {

    private Boolean success;

    private String modelType;

    private String modelId;

    private String providerKey;

    private Long elapsedMs;

    private String message;

    private String responsePreview;

    private Integer vectorDimension;

    @Builder.Default
    private List<Float> vectorPreview = new ArrayList<>();

    @Builder.Default
    private List<RerankItem> rerankResults = new ArrayList<>();

    private String errorMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankItem {
        private Integer rank;
        private Float score;
        private String text;
    }
}

