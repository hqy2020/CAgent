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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewQuestionCreateRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewQuestionPageRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewQuestionUpdateRequest;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewQuestionListVO;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewQuestionVO;

/**
 * 面试题目服务接口
 */
public interface InterviewQuestionService {

    /**
     * 分页查询面试题目
     *
     * @param request 分页查询请求参数
     * @return 面试题目分页结果（不含答案）
     */
    IPage<InterviewQuestionListVO> pageQuestions(InterviewQuestionPageRequest request);

    /**
     * 根据ID查询面试题目详情（含答案）
     *
     * @param id 题目ID
     * @return 面试题目详情
     */
    InterviewQuestionVO getById(Long id);

    /**
     * 创建面试题目
     *
     * @param request 创建请求参数
     */
    void createQuestion(InterviewQuestionCreateRequest request);

    /**
     * 更新面试题目
     *
     * @param request 更新请求参数
     */
    void updateQuestion(InterviewQuestionUpdateRequest request);

    /**
     * 删除面试题目
     *
     * @param id 题目ID
     */
    void deleteQuestion(Long id);
}
