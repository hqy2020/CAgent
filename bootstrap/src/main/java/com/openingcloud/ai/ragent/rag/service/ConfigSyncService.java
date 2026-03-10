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

/**
 * 配置同步服务 — 将 DB 中的配置同步到 Spring 单例 Bean
 */
public interface ConfigSyncService {

    /**
     * 从 DB 同步 AI 模型相关配置到 AIModelProperties
     */
    void syncAIModelProperties();

    /**
     * 从 DB 同步系统配置到各 Properties Bean
     */
    void syncSystemConfig();
}
