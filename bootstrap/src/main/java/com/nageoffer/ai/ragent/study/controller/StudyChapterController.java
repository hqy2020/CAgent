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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.study.controller.request.StudyChapterCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyChapterUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyChapterVO;
import com.nageoffer.ai.ragent.study.service.StudyChapterService;
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
 * 学习章节控制器
 */
@RestController
@RequiredArgsConstructor
public class StudyChapterController {

    private final StudyChapterService studyChapterService;

    /**
     * 根据模块ID查询章节列表
     */
    @GetMapping("/study/chapters")
    public Result<List<StudyChapterVO>> listByModuleId(@RequestParam Long moduleId) {
        return Results.success(studyChapterService.listByModuleId(moduleId));
    }

    /**
     * 创建学习章节
     */
    @PostMapping("/study/chapters")
    public Result<Void> createChapter(@RequestBody StudyChapterCreateRequest request) {
        studyChapterService.createChapter(request);
        return Results.success();
    }

    /**
     * 更新学习章节
     */
    @PutMapping("/study/chapters")
    public Result<Void> updateChapter(@RequestBody StudyChapterUpdateRequest request) {
        studyChapterService.updateChapter(request);
        return Results.success();
    }

    /**
     * 删除学习章节
     */
    @DeleteMapping("/study/chapters/{id}")
    public Result<Void> deleteChapter(@PathVariable Long id) {
        studyChapterService.deleteChapter(id);
        return Results.success();
    }
}
