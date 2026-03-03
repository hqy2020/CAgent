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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.vo.SystemConfigGroupVO;

import java.util.List;
import java.util.Map;

/**
 * 系统配置管理服务
 */
public interface SystemConfigService {

    /**
     * 获取所有可编辑配置（合并 DB 和内存值）
     */
    List<SystemConfigGroupVO> listConfigGroups();

    /**
     * 按分组批量更新配置
     */
    void updateConfigGroup(String group, Map<String, String> values);

    /**
     * 从 YAML 初始化 DB 数据（仅表为空时生效）
     */
    void initFromYaml();
}
