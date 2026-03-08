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

package com.nageoffer.ai.ragent.rag.core.hotspot;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 热点数据源。
 */
public enum HotspotSource {

    TWITTER("twitter", "Twitter"),
    BING("bing", "Bing"),
    HACKERNEWS("hackernews", "Hacker News"),
    SOGOU("sogou", "搜狗"),
    BILIBILI("bilibili", "Bilibili"),
    WEIBO("weibo", "微博"),
    BAIDU("baidu", "百度"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    REDDIT("reddit", "Reddit");

    private final String code;

    private final String label;

    HotspotSource(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static HotspotSource fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (HotspotSource value : values()) {
            if (value.code.equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    public static List<HotspotSource> parseCsv(String rawSources) {
        if (StrUtil.isBlank(rawSources)) {
            return List.of();
        }
        List<HotspotSource> result = new ArrayList<>();
        for (String part : rawSources.split(",")) {
            HotspotSource source = fromCode(part);
            if (source != null && !result.contains(source)) {
                result.add(source);
            }
        }
        return result;
    }

    public static List<HotspotSource> parseList(Collection<String> rawSources) {
        if (rawSources == null || rawSources.isEmpty()) {
            return List.of();
        }
        List<HotspotSource> result = new ArrayList<>();
        for (String rawSource : rawSources) {
            HotspotSource source = fromCode(rawSource);
            if (source != null && !result.contains(source)) {
                result.add(source);
            }
        }
        return result;
    }
}
