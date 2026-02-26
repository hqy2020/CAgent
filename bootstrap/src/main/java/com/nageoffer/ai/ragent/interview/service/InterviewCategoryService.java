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

package com.nageoffer.ai.ragent.interview.service;

import com.nageoffer.ai.ragent.interview.controller.request.InterviewCategoryCreateRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewCategoryUpdateRequest;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewCategoryVO;

import java.util.List;

/**
 * 面试分类服务接口
 */
public interface InterviewCategoryService {

    /**
     * 查询所有面试分类
     *
     * @return 面试分类列表
     */
    List<InterviewCategoryVO> listCategories();

    /**
     * 创建面试分类
     *
     * @param request 创建请求参数
     */
    void createCategory(InterviewCategoryCreateRequest request);

    /**
     * 更新面试分类
     *
     * @param request 更新请求参数
     */
    void updateCategory(InterviewCategoryUpdateRequest request);

    /**
     * 删除面试分类
     *
     * @param id 分类ID
     */
    void deleteCategory(Long id);
}
