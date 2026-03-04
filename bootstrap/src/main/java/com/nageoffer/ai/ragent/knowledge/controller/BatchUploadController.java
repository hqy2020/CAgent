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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.controller.request.BatchUploadCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.BatchUploadTaskVO;
import com.nageoffer.ai.ragent.knowledge.service.BatchUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量上传管理控制器
 */
@RestController
@RequiredArgsConstructor
@Validated
public class BatchUploadController {

    private final BatchUploadService batchUploadService;

    /**
     * 创建批量上传任务
     */
    @PostMapping("/knowledge-base/{kb-id}/batch-upload")
    public Result<BatchUploadTaskVO> createBatchTask(@PathVariable("kb-id") String kbId,
                                                     @RequestBody BatchUploadCreateRequest request) {
        return Results.success(batchUploadService.createBatchTask(kbId, request));
    }

    /**
     * 上传单个文件
     */
    @PostMapping(value = "/knowledge-base/batch-upload/{batch-id}/items/{item-id}/upload",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Void> uploadItem(@PathVariable("batch-id") String batchId,
                                   @PathVariable("item-id") String itemId,
                                   @RequestPart("file") MultipartFile file) {
        batchUploadService.uploadItem(batchId, itemId, file);
        return Results.success();
    }

    /**
     * 触发批量入库处理
     */
    @PostMapping("/knowledge-base/batch-upload/{batch-id}/start")
    public Result<Void> startBatchProcess(@PathVariable("batch-id") String batchId) {
        batchUploadService.startBatchProcess(batchId);
        return Results.success();
    }

    /**
     * 查询批量任务进度
     */
    @GetMapping("/knowledge-base/batch-upload/{batch-id}")
    public Result<BatchUploadTaskVO> getProgress(@PathVariable("batch-id") String batchId) {
        return Results.success(batchUploadService.getProgress(batchId));
    }
}
