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

package com.openingcloud.ai.ragent.knowledge.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 文档类型与数据通道推荐结果
 */
@Data
@Builder
public class KnowledgeDocumentSuggestionVO {

    private String sourceType;
    private String fileName;
    private String mimeType;
    private String docType;
    private String docTypeLabel;
    private String reason;
    private Double confidence;
    private String processMode;
    private String pipelineId;
    private String pipelineName;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer overlapSize;
    private Integer targetChars;
    private Integer maxChars;
    private Integer minChars;
    private Integer overlapChars;
}
