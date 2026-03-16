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

package com.openingcloud.ai.ragent.ingestion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 意图树冷启动初始化服务。
 *
 * <p>意图树数据已完全由数据库管理（t_intent_node 表），
 * 不再依赖 IntentTreeFactory 代码初始化。
 * 此类保留为向后兼容入口，方法均为 no-op。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeBootstrapService {

    /**
     * 手动触发初始化（no-op，数据已在 DB 中管理）
     */
    public int initializeManually() {
        log.info("意图树数据已由数据库管理，initFromFactory 已废弃，跳过");
        return 0;
    }

    /**
     * 手动触发工厂定义覆盖同步（no-op，数据已在 DB 中管理）
     */
    public IntentTreeSyncResult syncManually() {
        log.info("意图树数据已由数据库管理，syncFromFactory 已废弃，跳过");
        return new IntentTreeSyncResult(0, 0, 0);
    }
}
