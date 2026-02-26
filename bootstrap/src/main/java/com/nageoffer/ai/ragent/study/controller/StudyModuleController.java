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
import com.nageoffer.ai.ragent.study.controller.request.StudyModuleCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyModuleUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyModuleTreeVO;
import com.nageoffer.ai.ragent.study.controller.vo.StudyModuleVO;
import com.nageoffer.ai.ragent.study.service.StudyModuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学习模块控制器
 */
@RestController
@RequiredArgsConstructor
public class StudyModuleController {

    private final StudyModuleService studyModuleService;

    /**
     * 分页查询学习模块
     */
    @GetMapping("/study/modules")
    public Result<IPage<StudyModuleVO>> pageModules(@RequestParam(defaultValue = "1") int pageNo,
                                                    @RequestParam(defaultValue = "10") int pageSize) {
        return Results.success(studyModuleService.pageModules(pageNo, pageSize));
    }

    /**
     * 获取学习模块树形结构
     */
    @GetMapping("/study/modules/tree")
    public Result<List<StudyModuleTreeVO>> tree() {
        return Results.success(studyModuleService.tree());
    }

    /**
     * 创建学习模块
     */
    @PostMapping("/study/modules")
    public Result<Void> createModule(@RequestBody StudyModuleCreateRequest request) {
        studyModuleService.createModule(request);
        return Results.success();
    }

    /**
     * 更新学习模块
     */
    @PutMapping("/study/modules")
    public Result<Void> updateModule(@RequestBody StudyModuleUpdateRequest request) {
        studyModuleService.updateModule(request);
        return Results.success();
    }

    /**
     * 删除学习模块
     */
    @DeleteMapping("/study/modules/{id}")
    public Result<Void> deleteModule(@PathVariable Long id) {
        studyModuleService.deleteModule(id);
        return Results.success();
    }
}
