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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCaseBatchImportRequest;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCaseCreateRequest;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCreateRequest;
import com.openingcloud.ai.ragent.evaluation.controller.request.ImportFromChatRequest;
import com.openingcloud.ai.ragent.evaluation.controller.vo.ConversationQAPairVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalDatasetCaseVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalDatasetVO;
import com.openingcloud.ai.ragent.evaluation.service.EvalDatasetGenerateService;
import com.openingcloud.ai.ragent.evaluation.service.EvalDatasetService;
import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测数据集控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/evaluation/datasets")
public class EvaluationDatasetController {

    private final EvalDatasetService evalDatasetService;
    private final EvalDatasetGenerateService evalDatasetGenerateService;

    @PostMapping
    public Result<EvalDatasetVO> createDataset(@RequestBody EvalDatasetCreateRequest request) {
        return Results.success(evalDatasetService.createDataset(request));
    }

    @GetMapping
    public Result<List<EvalDatasetVO>> listDatasets() {
        return Results.success(evalDatasetService.listDatasets());
    }

    @GetMapping("/conversations")
    public Result<IPage<ConversationQAPairVO>> listConversationQAPairs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return Results.success(evalDatasetService.listConversationQAPairs(keyword, current, size));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getDataset(@PathVariable Long id) {
        EvalDatasetVO dataset = evalDatasetService.getDataset(id);
        List<EvalDatasetCaseVO> cases = evalDatasetService.listCases(id);
        Map<String, Object> result = new HashMap<>();
        result.put("dataset", dataset);
        result.put("cases", cases);
        return Results.success(result);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteDataset(@PathVariable Long id) {
        evalDatasetService.deleteDataset(id);
        return Results.success();
    }

    @PostMapping("/{id}/cases")
    public Result<EvalDatasetCaseVO> addCase(@PathVariable Long id,
            @RequestBody EvalDatasetCaseCreateRequest request) {
        return Results.success(evalDatasetService.addCase(id, request));
    }

    @PostMapping("/{id}/cases/batch")
    public Result<Integer> batchImportCases(@PathVariable Long id,
            @RequestBody EvalDatasetCaseBatchImportRequest request) {
        return Results.success(evalDatasetService.batchImportCases(id, request.getCases()));
    }

    @DeleteMapping("/{id}/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable Long id, @PathVariable Long caseId) {
        evalDatasetService.deleteCase(id, caseId);
        return Results.success();
    }

    @PostMapping("/{id}/generate")
    public Result<Integer> generateCases(@PathVariable Long id,
            @RequestParam Long kbId,
            @RequestParam(defaultValue = "10") int count) {
        return Results.success(evalDatasetGenerateService.generateCases(id, kbId, count));
    }

    @PostMapping("/{id}/import-from-chat")
    public Result<Integer> importFromChat(@PathVariable Long id,
            @RequestBody ImportFromChatRequest request) {
        return Results.success(evalDatasetService.importFromChat(id, request.getMessageIds()));
    }
}
