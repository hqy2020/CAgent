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

package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.knowledge.controller.request.BatchUploadCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.BatchUploadTaskVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量上传服务接口
 */
public interface BatchUploadService {

    /**
     * 创建批量上传任务
     *
     * @param kbId    知识库 ID
     * @param request 创建请求
     * @return 批量任务 VO
     */
    BatchUploadTaskVO createBatchTask(String kbId, BatchUploadCreateRequest request);

    /**
     * 上传单个文件到 S3
     *
     * @param batchId 批量任务 ID
     * @param itemId  子项 ID
     * @param file    文件
     */
    void uploadItem(String batchId, String itemId, MultipartFile file);

    /**
     * 触发批量入库处理
     *
     * @param batchId 批量任务 ID
     */
    void startBatchProcess(String batchId);

    /**
     * 查询批量任务进度
     *
     * @param batchId 批量任务 ID
     * @return 批量任务 VO（含子项列表）
     */
    BatchUploadTaskVO getProgress(String batchId);
}
