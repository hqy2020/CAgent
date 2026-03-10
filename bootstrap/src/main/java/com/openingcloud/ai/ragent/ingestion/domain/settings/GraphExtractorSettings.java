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

package com.openingcloud.ai.ragent.ingestion.domain.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱抽取节点配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphExtractorSettings {

    /**
     * 使用的 LLM 模型 ID，为空则使用默认模型
     */
    private String model;

    /**
     * 每篇文档最多抽取的三元组数量
     */
    @Builder.Default
    private int maxTriples = 30;

    /**
     * 是否在插入前先删除旧数据
     */
    @Builder.Default
    private boolean deleteBeforeInsert = true;
}
