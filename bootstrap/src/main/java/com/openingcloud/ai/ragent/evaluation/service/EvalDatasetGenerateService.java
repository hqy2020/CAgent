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

package com.openingcloud.ai.ragent.evaluation.service;

/**
 * 评测数据集自动生成服务
 */
public interface EvalDatasetGenerateService {

    /**
     * 从知识库随机抽取 chunks，用 LLM 生成 QA 对，添加到数据集
     *
     * @param datasetId 数据集ID
     * @param kbId      知识库ID
     * @param count     期望生成的用例数量
     * @return 实际成功生成的用例数量
     */
    int generateCases(Long datasetId, Long kbId, int count);
}
