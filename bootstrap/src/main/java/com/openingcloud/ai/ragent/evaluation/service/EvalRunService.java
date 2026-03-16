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

import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunCompareVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunReportVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunResultVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunVO;

import java.util.List;

/**
 * 评测运行服务接口
 */
public interface EvalRunService {

    /**
     * 触发评测运行
     *
     * @param datasetId 数据集ID
     * @return 运行记录
     */
    EvalRunVO triggerRun(Long datasetId);

    /**
     * 查询所有运行记录
     *
     * @return 运行记录列表
     */
    List<EvalRunVO> listRuns();

    /**
     * 查询单个运行记录
     *
     * @param runId 运行ID
     * @return 运行记录
     */
    EvalRunVO getRun(Long runId);

    /**
     * 获取运行报告
     *
     * @param runId 运行ID
     * @return 运行报告
     */
    EvalRunReportVO getReport(Long runId);

    /**
     * 分页查询运行结果
     *
     * @param runId 运行ID
     * @param page  页码
     * @param size  每页大小
     * @return 结果列表
     */
    List<EvalRunResultVO> getResults(Long runId, Integer page, Integer size);

    /**
     * 查询 Bad Case 列表
     *
     * @param runId 运行ID
     * @return Bad Case 列表
     */
    List<EvalRunResultVO> getBadCases(Long runId);

    /**
     * 对比两次评测运行的结果
     *
     * @param baseRunId    基准运行ID
     * @param compareRunId 对比运行ID
     * @return 对比结果
     */
    EvalRunCompareVO compareRuns(Long baseRunId, Long compareRunId);
}
