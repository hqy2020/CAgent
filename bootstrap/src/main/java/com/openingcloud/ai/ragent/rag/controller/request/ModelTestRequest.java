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

package com.openingcloud.ai.ragent.rag.controller.request;

import lombok.Data;

import java.util.List;

/**
 * 模型连通性测试请求
 */
@Data
public class ModelTestRequest {

    /**
     * Chat / Embedding 测试输入
     */
    private String input;

    /**
     * Rerank 测试查询词
     */
    private String query;

    /**
     * Rerank 候选文本列表
     */
    private List<String> candidates;

    /**
     * Rerank 返回条数
     */
    private Integer topN;

    /**
     * Chat 是否启用深度思考模式
     */
    private Boolean thinking;
}

