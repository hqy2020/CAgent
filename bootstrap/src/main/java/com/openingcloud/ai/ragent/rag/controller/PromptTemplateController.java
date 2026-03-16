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

package com.openingcloud.ai.ragent.rag.controller;

import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.web.Results;
import com.openingcloud.ai.ragent.rag.controller.request.PromptTemplateUpdateRequest;
import com.openingcloud.ai.ragent.rag.controller.vo.PromptTemplateVO;
import com.openingcloud.ai.ragent.rag.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词模板管理接口（Admin）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/prompts")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    /**
     * 查询提示词模板列表
     *
     * @param category 可选分类过滤
     * @return 模板列表
     */
    @GetMapping
    public Result<List<PromptTemplateVO>> listTemplates(
            @RequestParam(required = false) String category) {
        return Results.success(promptTemplateService.listTemplates(category));
    }

    /**
     * 获取单个提示词模板详情
     *
     * @param key 模板标识键
     * @return 模板详情
     */
    @GetMapping("/{key}")
    public Result<PromptTemplateVO> getTemplate(@PathVariable String key) {
        return Results.success(promptTemplateService.getTemplate(key));
    }

    /**
     * 更新提示词模板内容
     *
     * @param key     模板标识键
     * @param request 更新请求
     * @return 更新后的模板
     */
    @PutMapping("/{key}")
    public Result<PromptTemplateVO> updateTemplate(
            @PathVariable String key, @RequestBody PromptTemplateUpdateRequest request) {
        return Results.success(promptTemplateService.updateTemplate(key, request.getContent()));
    }

    /**
     * 重置模板为文件版本
     *
     * @param key 模板标识键
     * @return 空
     */
    @PostMapping("/{key}/reset")
    public Result<Void> resetTemplate(@PathVariable String key) {
        promptTemplateService.resetTemplate(key);
        return Results.success();
    }

    /**
     * 切换模板启用/禁用状态
     *
     * @param key 模板标识键
     * @return 空
     */
    @PostMapping("/{key}/toggle")
    public Result<Void> toggleTemplate(@PathVariable String key) {
        promptTemplateService.toggleTemplate(key);
        return Results.success();
    }
}
