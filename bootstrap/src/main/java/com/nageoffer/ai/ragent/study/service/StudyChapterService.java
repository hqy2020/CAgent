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

package com.nageoffer.ai.ragent.study.service;

import com.nageoffer.ai.ragent.study.controller.request.StudyChapterCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyChapterUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyChapterVO;

import java.util.List;

/**
 * 学习章节服务接口
 */
public interface StudyChapterService {

    /**
     * 根据模块ID查询章节列表
     *
     * @param moduleId 模块ID
     * @return 章节列表
     */
    List<StudyChapterVO> listByModuleId(Long moduleId);

    /**
     * 创建学习章节
     *
     * @param request 创建请求参数
     */
    void createChapter(StudyChapterCreateRequest request);

    /**
     * 更新学习章节
     *
     * @param request 更新请求参数
     */
    void updateChapter(StudyChapterUpdateRequest request);

    /**
     * 删除学习章节
     *
     * @param id 章节ID
     */
    void deleteChapter(Long id);
}
