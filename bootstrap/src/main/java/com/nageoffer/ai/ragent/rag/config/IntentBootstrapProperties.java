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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图树初始化配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent.bootstrap")
public class IntentBootstrapProperties {

    /**
     * 是否在启动时自动执行初始化
     */
    private boolean enabled = true;

    /**
     * 初始化策略
     */
    private String strategy = "from-existing-kb";

    /**
     * 是否包含系统意图节点
     */
    private boolean includeSystem = true;
}
