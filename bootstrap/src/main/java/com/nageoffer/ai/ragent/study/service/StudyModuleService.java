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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.study.controller.request.StudyModuleCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyModuleUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyModuleTreeVO;
import com.nageoffer.ai.ragent.study.controller.vo.StudyModuleVO;

import java.util.List;

/**
 * 学习模块服务接口
 */
public interface StudyModuleService {

    /**
     * 分页查询学习模块
     *
     * @param pageNo   页码
     * @param pageSize 每页大小
     * @return 学习模块分页结果
     */
    IPage<StudyModuleVO> pageModules(int pageNo, int pageSize);

    /**
     * 获取学习模块树形结构（模块 → 章节 → 文档标题）
     *
     * @return 学习模块树形结构列表
     */
    List<StudyModuleTreeVO> tree();

    /**
     * 创建学习模块
     *
     * @param request 创建请求参数
     */
    void createModule(StudyModuleCreateRequest request);

    /**
     * 更新学习模块
     *
     * @param request 更新请求参数
     */
    void updateModule(StudyModuleUpdateRequest request);

    /**
     * 删除学习模块
     *
     * @param id 模块ID
     */
    void deleteModule(Long id);
}
