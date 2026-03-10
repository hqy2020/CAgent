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

package com.openingcloud.ai.ragent.rag.mcp;

import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAggregationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.browser.BrowserMcpNewsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserMcpNewsServiceTests {

    @Test
    void shouldReturnFailureWhenNoHotspotsFound() {
        HotspotAggregationService hotspotAggregationService = mock(HotspotAggregationService.class);
        when(hotspotAggregationService.search(any(), any(), anyInt())).thenReturn(
                HotspotReport.builder()
                        .query("AI")
                        .generatedAt(System.currentTimeMillis())
                        .total(0)
                        .sources(List.of("bing"))
                        .sourceCounts(Map.of())
                        .warnings(List.of("Bing 抓取失败：HTTP 429"))
                        .items(List.of())
                        .build()
        );

        BrowserMcpNewsService service = new BrowserMcpNewsService(hotspotAggregationService);
        BrowserMcpNewsService.NewsSearchResult result = service.search("AI", 3);

        assertFalse(result.success());
        assertTrue(result.toText("AI").contains("Bing 抓取失败"));
    }

    @Test
    void shouldFormatStructuredResultsFromAggregatedHotspots() {
        HotspotAggregationService hotspotAggregationService = mock(HotspotAggregationService.class);
        when(hotspotAggregationService.search(any(), any(), anyInt())).thenReturn(
                HotspotReport.builder()
                        .query("AI")
                        .generatedAt(System.currentTimeMillis())
                        .total(3)
                        .sources(List.of("bing", "hackernews"))
                        .sourceCounts(Map.of("hackernews", 1, "bilibili", 1, "bing", 1))
                        .warnings(List.of())
                        .items(List.of(
                                HotspotSearchItem.builder()
                                        .title("AI News A")
                                        .summary("summary-a")
                                        .url("https://example.com/a")
                                        .source("hackernews")
                                        .sourceLabel("Hacker News")
                                        .publishedAt(Instant.parse("2026-03-05T08:00:00Z"))
                                        .hotScore(88D)
                                        .build(),
                                HotspotSearchItem.builder()
                                        .title("AI News B")
                                        .summary("summary-b")
                                        .url("https://example.com/b")
                                        .source("bilibili")
                                        .sourceLabel("Bilibili")
                                        .publishedAt(Instant.parse("2026-03-05T09:00:00Z"))
                                        .hotScore(72D)
                                        .build(),
                                HotspotSearchItem.builder()
                                        .title("AI News C")
                                        .summary("summary-c")
                                        .url("https://example.com/c")
                                        .source("bing")
                                        .sourceLabel("Bing")
                                        .publishedAt(null)
                                        .hotScore(0D)
                                        .build()
                        ))
                        .build()
        );

        BrowserMcpNewsService service = new BrowserMcpNewsService(hotspotAggregationService);
        BrowserMcpNewsService.NewsSearchResult result = service.search("AI", 3);

        assertTrue(result.success());
        assertFalse(result.fallback());
        assertEquals(3, result.items().size());
        String text = result.toText("AI");
        assertTrue(text.contains("1. 标题：AI News A"));
        assertTrue(text.contains("来源：Hacker News"));
        assertTrue(text.contains("来源链接：https://example.com/a"));
        assertTrue(text.contains("发布日期：2026-03-05"));
        assertTrue(result.toData().toString().contains("Bilibili"));
    }
}
