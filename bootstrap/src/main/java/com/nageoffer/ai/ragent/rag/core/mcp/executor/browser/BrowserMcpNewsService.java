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

package com.nageoffer.ai.ragent.rag.core.mcp.executor.browser;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.hotspot.HotspotAggregationService;
import com.nageoffer.ai.ragent.rag.core.hotspot.HotspotReport;
import com.nageoffer.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.nageoffer.ai.ragent.rag.core.hotspot.HotspotSource;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网热点查询服务。
 *
 * <p>底层参考 yupi-hot-monitor 的多源抓取方案，
 * 聚合 Bing / Hacker News / 搜狗 / Bilibili 后返回结构化结果。
 */
@Service
@RequiredArgsConstructor
public class BrowserMcpNewsService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final HotspotAggregationService hotspotAggregationService;

    public NewsSearchResult search(String query, int limit) {
        HotspotReport report = hotspotAggregationService.search(
                query,
                List.of(
                        HotspotSource.BING,
                        HotspotSource.HACKERNEWS,
                        HotspotSource.SOGOU,
                        HotspotSource.BILIBILI
                ),
                Math.max(1, Math.min(limit, 5))
        );

        if (report.getItems() == null || report.getItems().isEmpty()) {
            String warning = report.getWarnings().isEmpty()
                    ? "未检索到可用的联网热点，请稍后再试。"
                    : String.join("；", report.getWarnings());
            return NewsSearchResult.failed(warning);
        }

        List<NewsItem> items = report.getItems().stream()
                .limit(Math.max(1, Math.min(limit, 5)))
                .map(this::toNewsItem)
                .toList();
        return NewsSearchResult.success(items);
    }

    private NewsItem toNewsItem(HotspotSearchItem item) {
        String date = item.getPublishedAt() == null ? "-" : DATE_FORMATTER.format(item.getPublishedAt());
        return new NewsItem(
                item.getTitle(),
                item.getUrl(),
                date,
                StrUtil.blankToDefault(item.getSourceLabel(), item.getSource())
        );
    }

    @Builder
    public record NewsSearchResult(boolean success,
                                   boolean fallback,
                                   String message,
                                   List<NewsItem> items) {

        public static NewsSearchResult success(List<NewsItem> items) {
            return NewsSearchResult.builder()
                    .success(true)
                    .fallback(false)
                    .items(items)
                    .build();
        }

        public static NewsSearchResult fallback(String message) {
            return NewsSearchResult.builder()
                    .success(true)
                    .fallback(true)
                    .message(message)
                    .items(List.of())
                    .build();
        }

        public static NewsSearchResult failed(String message) {
            return NewsSearchResult.builder()
                    .success(false)
                    .fallback(false)
                    .message(message)
                    .items(List.of())
                    .build();
        }

        public String toText(String query) {
            if (fallback) {
                return message;
            }
            if (!success || items == null || items.isEmpty()) {
                return StrUtil.blankToDefault(message, "联网热点检索失败。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("联网检索结果（关键词：").append(StrUtil.blankToDefault(query, "AI")).append("）：\n");
            for (int i = 0; i < items.size(); i++) {
                NewsItem item = items.get(i);
                sb.append(i + 1).append(". 标题：").append(item.title()).append("\n");
                sb.append("   来源：").append(item.source()).append("\n");
                sb.append("   来源链接：").append(item.url()).append("\n");
                sb.append("   发布日期：").append(item.date()).append("\n");
            }
            return sb.toString().trim();
        }

        public Map<String, Object> toData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fallback", fallback);
            data.put("message", message);
            List<Map<String, String>> normalizedItems = new ArrayList<>();
            if (items != null) {
                for (NewsItem item : items) {
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("title", item.title());
                    one.put("source", item.source());
                    one.put("url", item.url());
                    one.put("date", item.date());
                    normalizedItems.add(one);
                }
            }
            data.put("items", normalizedItems);
            return data;
        }
    }

    public record NewsItem(String title, String url, String date, String source) {
    }
}
