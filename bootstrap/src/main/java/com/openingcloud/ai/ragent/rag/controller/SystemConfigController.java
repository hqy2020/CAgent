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
import com.openingcloud.ai.ragent.rag.controller.vo.SystemConfigGroupVO;
import com.openingcloud.ai.ragent.rag.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统配置控制器（可编辑配置管理，与 RAGSettingsController 并存）
 */
@RestController
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @GetMapping("/system/configs")
    public Result<List<SystemConfigGroupVO>> listConfigs() {
        return Results.success(systemConfigService.listConfigGroups());
    }

    @PutMapping("/system/configs/{group}")
    public Result<Void> updateConfigGroup(@PathVariable String group, @RequestBody Map<String, String> values) {
        systemConfigService.updateConfigGroup(group, values);
        return Results.success();
    }

    @PostMapping("/system/configs/init-from-yaml")
    public Result<Void> initFromYaml() {
        systemConfigService.initFromYaml();
        return Results.success();
    }
}
