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

package com.openingcloud.ai.ragent.ingestion.service.impl;

import com.openingcloud.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化标准数据通道，避免查询路径写库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StandardIngestionPipelineInitializer {

    private final IngestionPipelineService pipelineService;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            pipelineService.initializeStandardPipelines();
        } catch (Exception e) {
            log.error("初始化标准数据通道失败，将降级为只读查询: {}", e.getMessage(), e);
        }
    }
}
