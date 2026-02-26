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
import com.nageoffer.ai.ragent.study.controller.request.StudyDocumentCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyDocumentUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyDocumentListVO;
import com.nageoffer.ai.ragent.study.controller.vo.StudyDocumentVO;

/**
 * 学习文档服务接口
 */
public interface StudyDocumentService {

    /**
     * 分页查询学习文档列表（不含内容）
     *
     * @param pageNo    页码
     * @param pageSize  每页大小
     * @param chapterId 章节ID（可选）
     * @param moduleId  模块ID（可选）
     * @return 学习文档分页结果
     */
    IPage<StudyDocumentListVO> pageDocuments(int pageNo, int pageSize, Long chapterId, Long moduleId);

    /**
     * 根据ID查询学习文档详情（含内容）
     *
     * @param id 文档ID
     * @return 学习文档详情
     */
    StudyDocumentVO getById(Long id);

    /**
     * 创建学习文档
     *
     * @param request 创建请求参数
     */
    void createDocument(StudyDocumentCreateRequest request);

    /**
     * 更新学习文档
     *
     * @param request 更新请求参数
     */
    void updateDocument(StudyDocumentUpdateRequest request);

    /**
     * 删除学习文档
     *
     * @param id 文档ID
     */
    void deleteDocument(Long id);
}
