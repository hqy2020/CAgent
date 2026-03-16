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

package com.openingcloud.ai.ragent.evaluation.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 评测运行结果明细表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_eval_run_result")
public class EvalRunResultDO {

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 运行ID
     */
    private Long runId;

    /**
     * 用例ID
     */
    private Long caseId;

    /**
     * 命中率
     */
    private BigDecimal hitRate;

    /**
     * MRR
     */
    private BigDecimal mrr;

    /**
     * 召回分数
     */
    private BigDecimal recallScore;

    /**
     * 精确分数
     */
    private BigDecimal precisionScore;

    /**
     * 检索到的分块ID列表（JSON）
     */
    private String retrievedChunkIds;

    /**
     * 生成的答案
     */
    private String generatedAnswer;

    /**
     * 忠实度分数
     */
    private BigDecimal faithfulnessScore;

    /**
     * 忠实度原因
     */
    private String faithfulnessReason;

    /**
     * 相关性分数
     */
    private BigDecimal relevancyScore;

    /**
     * 相关性原因
     */
    private String relevancyReason;

    /**
     * 正确性分数
     */
    private BigDecimal correctnessScore;

    /**
     * 正确性原因
     */
    private String correctnessReason;

    /**
     * 是否为兜底回答 0：否 1：是
     */
    private Integer isFallback;

    /**
     * 是否为Bad Case 0：否 1：是
     */
    private Integer isBadCase;

    /**
     * 根因分析
     */
    private String rootCause;

    /**
     * 延迟（毫秒）
     */
    private Long latencyMs;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
