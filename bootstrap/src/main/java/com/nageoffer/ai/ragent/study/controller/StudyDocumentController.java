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

package com.nageoffer.ai.ragent.study.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.study.controller.request.StudyDocumentCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyDocumentUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyDocumentListVO;
import com.nageoffer.ai.ragent.study.controller.vo.StudyDocumentVO;
import com.nageoffer.ai.ragent.study.service.StudyDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习文档控制器
 */
@RestController
@RequiredArgsConstructor
public class StudyDocumentController {

    private final StudyDocumentService studyDocumentService;

    /**
     * 分页查询学习文档列表
     */
    @GetMapping("/study/documents")
    public Result<IPage<StudyDocumentListVO>> pageDocuments(@RequestParam(defaultValue = "1") int pageNo,
                                                           @RequestParam(defaultValue = "10") int pageSize,
                                                           @RequestParam(required = false) Long chapterId,
                                                           @RequestParam(required = false) Long moduleId) {
        return Results.success(studyDocumentService.pageDocuments(pageNo, pageSize, chapterId, moduleId));
    }

    /**
     * 查询学习文档详情
     */
    @GetMapping("/study/documents/{id}")
    public Result<StudyDocumentVO> getById(@PathVariable Long id) {
        return Results.success(studyDocumentService.getById(id));
    }

    /**
     * 创建学习文档
     */
    @PostMapping("/study/documents")
    public Result<Void> createDocument(@RequestBody StudyDocumentCreateRequest request) {
        studyDocumentService.createDocument(request);
        return Results.success();
    }

    /**
     * 更新学习文档
     */
    @PutMapping("/study/documents")
    public Result<Void> updateDocument(@RequestBody StudyDocumentUpdateRequest request) {
        studyDocumentService.updateDocument(request);
        return Results.success();
    }

    /**
     * 删除学习文档
     */
    @DeleteMapping("/study/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        studyDocumentService.deleteDocument(id);
        return Results.success();
    }
}
