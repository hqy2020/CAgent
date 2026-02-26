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

package com.nageoffer.ai.ragent.study.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 学习模块树形结构 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudyModuleTreeVO {

    /**
     * 模块ID
     */
    private Long id;

    /**
     * 模块名称
     */
    private String name;

    /**
     * 模块图标
     */
    private String icon;

    /**
     * 章节列表
     */
    private List<ChapterNode> chapters;

    /**
     * 章节节点
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChapterNode {

        /**
         * 章节ID
         */
        private Long id;

        /**
         * 章节标题
         */
        private String title;

        /**
         * 文档列表
         */
        private List<DocumentNode> documents;
    }

    /**
     * 文档节点
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DocumentNode {

        /**
         * 文档ID
         */
        private Long id;

        /**
         * 文档标题
         */
        private String title;
    }
}
