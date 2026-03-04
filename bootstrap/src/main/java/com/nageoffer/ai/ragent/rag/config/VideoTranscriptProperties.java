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
 * VideoTranscriptAPI 集成配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "video-transcript")
public class VideoTranscriptProperties {

    /**
     * 是否启用转录工具
     */
    private boolean enabled = false;

    /**
     * VideoTranscriptAPI 服务地址，例如 http://localhost:8000
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * VideoTranscriptAPI Bearer Token
     */
    private String authToken = "";

    /**
     * 提交任务接口的超时秒数
     */
    private int submitTimeoutSeconds = 30;

    /**
     * 轮询任务状态的间隔（毫秒）
     */
    private int pollIntervalMillis = 3000;

    /**
     * 等待任务完成的最长时间（秒）
     */
    private int pollTimeoutSeconds = 1200;

    /**
     * 默认是否开启说话人识别
     */
    private boolean defaultUseSpeakerRecognition = false;

    /**
     * 默认写入的 Obsidian 目录
     */
    private String defaultNotePath = "2-Resource（参考资源）/30_学习输入/视频转录";

    /**
     * 当目标笔记已存在时，默认是否追加写入
     */
    private boolean appendIfExists = true;
}

