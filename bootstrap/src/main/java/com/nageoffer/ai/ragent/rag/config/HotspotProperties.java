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

import java.util.ArrayList;
import java.util.List;

/**
 * 热点聚合抓取配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.hotspot")
public class HotspotProperties {

    /**
     * 是否启用多源热点抓取。
     */
    private boolean enabled = true;

    /**
     * 单次抓取的网络超时时间。
     */
    private int timeoutSeconds = 12;

    /**
     * 每个数据源最多抓取多少条原始结果。
     */
    private int maxResultsPerSource = 8;

    /**
     * 是否启用定时监控扫描。
     */
    private boolean monitorEnabled = true;

    /**
     * 定时扫描间隔。
     */
    private long scanDelayMs = 10000L;

    /**
     * 单机扫描批量大小。
     */
    private int scanBatchSize = 10;

    /**
     * 监控任务锁定秒数。
     */
    private long lockSeconds = 300L;

    /**
     * 单个监控任务默认返回的热点条目数。
     */
    private int monitorResultLimit = 12;

    /**
     * 手工报表默认是否执行 AI 分析。
     */
    private boolean reportAnalyzeEnabled = true;

    /**
     * 监控场景最多分析多少条热点。
     */
    private int analysisTopN = 8;

    /**
     * 是否启用 AI 分析。
     */
    private boolean analysisEnabled = true;

    /**
     * 是否启用 WebSocket 推送。
     */
    private boolean websocketEnabled = true;

    /**
     * Twitter 数据源 API Key。
     */
    private String twitterApiKey;

    /**
     * 默认启用的数据源。
     */
    private List<String> defaultSources = new ArrayList<>(List.of(
            "bing",
            "hackernews",
            "sogou",
            "bilibili",
            "weibo",
            "baidu",
            "duckduckgo",
            "reddit"
    ));
}
