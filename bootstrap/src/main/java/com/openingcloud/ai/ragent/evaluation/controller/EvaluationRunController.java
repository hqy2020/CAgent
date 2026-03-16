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

package com.openingcloud.ai.ragent.evaluation.controller;

import com.openingcloud.ai.ragent.evaluation.controller.request.EvalRunTriggerRequest;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunCompareVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunReportVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunResultVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunVO;
import com.openingcloud.ai.ragent.evaluation.service.EvalRunService;
import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测运行控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/evaluation/runs")
public class EvaluationRunController {

    private final EvalRunService evalRunService;

    @PostMapping
    public Result<EvalRunVO> triggerRun(@RequestBody EvalRunTriggerRequest request) {
        return Results.success(evalRunService.triggerRun(request.getDatasetId()));
    }

    @GetMapping
    public Result<List<EvalRunVO>> listRuns() {
        return Results.success(evalRunService.listRuns());
    }

    @GetMapping("/{id}")
    public Result<EvalRunVO> getRun(@PathVariable Long id) {
        return Results.success(evalRunService.getRun(id));
    }

    @GetMapping("/{id}/report")
    public Result<EvalRunReportVO> getReport(@PathVariable Long id) {
        return Results.success(evalRunService.getReport(id));
    }

    @GetMapping("/{id}/results")
    public Result<List<EvalRunResultVO>> getResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Results.success(evalRunService.getResults(id, page, size));
    }

    @GetMapping("/{id}/bad-cases")
    public Result<List<EvalRunResultVO>> getBadCases(@PathVariable Long id) {
        return Results.success(evalRunService.getBadCases(id));
    }

    @GetMapping("/compare")
    public Result<EvalRunCompareVO> compareRuns(@RequestParam Long baseRunId,
            @RequestParam Long compareRunId) {
        return Results.success(evalRunService.compareRuns(baseRunId, compareRunId));
    }
}
