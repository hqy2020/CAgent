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

package com.nageoffer.ai.ragent.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 参考文档引用条目
 *
 * @param documentId        文档 ID
 * @param documentName      文档名称
 * @param knowledgeBaseId   知识库 ID
 * @param knowledgeBaseName 知识库名称
 * @param score             匹配得分（文档级，取所有 chunks 中最高分）
 * @param documentUrl       完整文档访问地址（优先 sourceLocation，其次 fileUrl）
 * @param textPreview       文本摘要（最高分 chunk 文本）
 * @param chunks            该文档所有命中片段（按 score 降序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceItem(
        String documentId,
        String documentName,
        String knowledgeBaseId,
        String knowledgeBaseName,
        Float score,
        String documentUrl,
        String textPreview,
        List<ChunkDetail> chunks
) {

    /**
     * 单个命中片段详情
     *
     * @param content 完整 chunk 文本
     * @param score   该 chunk 的检索得分
     */
    public record ChunkDetail(
            String content,
            Float score
    ) {
    }
}
