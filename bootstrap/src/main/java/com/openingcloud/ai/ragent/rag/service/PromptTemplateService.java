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

package com.openingcloud.ai.ragent.rag.service;

import com.openingcloud.ai.ragent.rag.controller.vo.PromptTemplateVO;

import java.util.List;

/**
 * 提示词模板管理服务
 */
public interface PromptTemplateService {

    /**
     * 查询模板列表
     *
     * @param category 可选分类过滤（SYSTEM / SCENE / FLOW / EVAL）
     * @return 模板列表
     */
    List<PromptTemplateVO> listTemplates(String category);

    /**
     * 获取单个模板详情
     *
     * @param promptKey 模板标识键
     * @return 模板详情
     */
    PromptTemplateVO getTemplate(String promptKey);

    /**
     * 更新模板内容（同时启用该模板的 DB 版本）
     *
     * @param promptKey 模板标识键
     * @param content   新的模板内容
     * @return 更新后的模板
     */
    PromptTemplateVO updateTemplate(String promptKey, String content);

    /**
     * 重置模板为文件版本（禁用 DB 覆盖）
     *
     * @param promptKey 模板标识键
     */
    void resetTemplate(String promptKey);

    /**
     * 切换模板启用/禁用状态
     *
     * @param promptKey 模板标识键
     */
    void toggleTemplate(String promptKey);
}
