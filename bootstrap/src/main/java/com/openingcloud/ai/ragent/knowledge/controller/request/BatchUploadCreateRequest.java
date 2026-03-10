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

package com.openingcloud.ai.ragent.knowledge.controller.request;

import lombok.Data;

import java.util.List;

/**
 * 批量上传创建请求
 */
@Data
public class BatchUploadCreateRequest {

    /**
     * 文件名列表
     */
    private List<String> fileNames;

    /**
     * 处理模式：chunk / pipeline
     */
    private String processMode;

    /**
     * 分块策略：fixed_size / structure_aware
     */
    private String chunkStrategy;

    /**
     * 分块参数JSON
     */
    private String chunkConfig;

    /**
     * 固定大小分块：块大小
     */
    private Integer chunkSize;

    /**
     * 固定大小分块：重叠大小
     */
    private Integer overlapSize;

    /**
     * 结构感知：理想块大小
     */
    private Integer targetChars;

    /**
     * 结构感知：块上限
     */
    private Integer maxChars;

    /**
     * 结构感知：块下限
     */
    private Integer minChars;

    /**
     * 结构感知：重叠大小
     */
    private Integer overlapChars;

    /**
     * 数据通道ID
     */
    private String pipelineId;
}
