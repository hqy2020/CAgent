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

package com.openingcloud.ai.ragent.evaluation.engine;

import cn.hutool.core.collection.CollUtil;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索阶段评估器
 * 提供命中率、MRR、召回率、精确率等检索质量指标的计算
 */
@Service
public class RetrievalEvaluator {

    /**
     * 计算命中率：retrievedChunkIds 中是否包含任一 relevantChunkId
     */
    public double hitRate(List<String> retrievedChunkIds, List<String> relevantChunkIds) {
        if (CollUtil.isEmpty(relevantChunkIds)) {
            return 1.0;
        }
        if (CollUtil.isEmpty(retrievedChunkIds)) {
            return 0.0;
        }
        for (String id : relevantChunkIds) {
            if (retrievedChunkIds.contains(id)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * MRR: 第一个命中的相关 chunk 的倒数排名
     */
    public double mrr(List<String> retrievedChunkIds, List<String> relevantChunkIds) {
        if (CollUtil.isEmpty(relevantChunkIds) || CollUtil.isEmpty(retrievedChunkIds)) {
            return 0.0;
        }
        Set<String> relevant = new HashSet<>(relevantChunkIds);
        for (int i = 0; i < retrievedChunkIds.size(); i++) {
            if (relevant.contains(retrievedChunkIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Recall: |交集| / |relevantChunkIds|
     */
    public double recall(List<String> retrievedChunkIds, List<String> relevantChunkIds) {
        if (CollUtil.isEmpty(relevantChunkIds)) {
            return 1.0;
        }
        if (CollUtil.isEmpty(retrievedChunkIds)) {
            return 0.0;
        }
        Set<String> retrieved = new HashSet<>(retrievedChunkIds);
        long hits = relevantChunkIds.stream().filter(retrieved::contains).count();
        return (double) hits / relevantChunkIds.size();
    }

    /**
     * Precision: |交集| / |retrievedChunkIds|
     */
    public double precision(List<String> retrievedChunkIds, List<String> relevantChunkIds) {
        if (CollUtil.isEmpty(retrievedChunkIds)) {
            return 0.0;
        }
        if (CollUtil.isEmpty(relevantChunkIds)) {
            return 0.0;
        }
        Set<String> relevant = new HashSet<>(relevantChunkIds);
        long hits = retrievedChunkIds.stream().filter(relevant::contains).count();
        return (double) hits / retrievedChunkIds.size();
    }
}
