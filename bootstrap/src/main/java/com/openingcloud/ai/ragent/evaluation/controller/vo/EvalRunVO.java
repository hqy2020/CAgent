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

package com.openingcloud.ai.ragent.evaluation.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunVO {

    private String id;

    private String datasetId;

    private String datasetName;

    private String status;

    private Integer totalCases;

    private Integer completedCases;

    private BigDecimal avgHitRate;

    private BigDecimal avgMrr;

    private BigDecimal avgRecall;

    private BigDecimal avgPrecision;

    private BigDecimal avgFaithfulness;

    private BigDecimal avgRelevancy;

    private BigDecimal avgCorrectness;

    private Integer badCaseCount;

    private String errorMessage;

    private Date startedAt;

    private Date finishedAt;

    private Date createTime;
}
