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

package com.nageoffer.ai.ragent.interview.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewCategoryCreateRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewCategoryUpdateRequest;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewCategoryVO;
import com.nageoffer.ai.ragent.interview.service.InterviewCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试分类控制器
 */
@RestController
@RequiredArgsConstructor
public class InterviewCategoryController {

    private final InterviewCategoryService interviewCategoryService;

    /**
     * 查询所有面试分类
     */
    @GetMapping("/interview/categories")
    public Result<List<InterviewCategoryVO>> listCategories() {
        return Results.success(interviewCategoryService.listCategories());
    }

    /**
     * 创建面试分类
     */
    @PostMapping("/interview/categories")
    public Result<Void> createCategory(@RequestBody InterviewCategoryCreateRequest request) {
        interviewCategoryService.createCategory(request);
        return Results.success();
    }

    /**
     * 更新面试分类
     */
    @PutMapping("/interview/categories")
    public Result<Void> updateCategory(@RequestBody InterviewCategoryUpdateRequest request) {
        interviewCategoryService.updateCategory(request);
        return Results.success();
    }

    /**
     * 删除面试分类
     */
    @DeleteMapping("/interview/categories/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        interviewCategoryService.deleteCategory(id);
        return Results.success();
    }
}
