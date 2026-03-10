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
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAnalysisService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.browser.BrowserMcpWebSearchService;
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

class BrowserMcpWebSearchServiceTests {

    @Test
    void shouldReturnFailureWhenNoWebResultsFound() {
        HotspotAggregationService hotspotAggregationService = mock(HotspotAggregationService.class);
        HotspotAnalysisService hotspotAnalysisService = mock(HotspotAnalysisService.class);
        when(hotspotAnalysisService.expandKeyword(any())).thenReturn(List.of("1688-JAVA-工厂技术"));
        when(hotspotAggregationService.search(any(), any(), any(), anyInt())).thenReturn(
                HotspotReport.builder()
                        .query("1688-JAVA-工厂技术 做什么的")
                        .generatedAt(System.currentTimeMillis())
                        .total(0)
                        .sources(List.of("bing"))
                        .sourceCounts(Map.of())
                        .warnings(List.of("Bing 抓取失败：HTTP 429"))
                        .items(List.of())
                        .build()
        );

        BrowserMcpWebSearchService service = new BrowserMcpWebSearchService(hotspotAggregationService, hotspotAnalysisService);
        BrowserMcpWebSearchService.WebSearchResult result = service.search("1688-JAVA-工厂技术 做什么的", 5);

        assertFalse(result.success());
        assertTrue(result.toText("1688-JAVA-工厂技术 做什么的").contains("Bing 抓取失败"));
    }

    @Test
    void shouldFormatStructuredWebResults() {
        HotspotAggregationService hotspotAggregationService = mock(HotspotAggregationService.class);
        HotspotAnalysisService hotspotAnalysisService = mock(HotspotAnalysisService.class);
        when(hotspotAnalysisService.expandKeyword(any())).thenReturn(List.of("1688-JAVA-工厂技术", "1688"));
        when(hotspotAggregationService.search(any(), any(), any(), anyInt())).thenReturn(
                HotspotReport.builder()
                        .query("1688-JAVA-工厂技术 做什么的")
                        .generatedAt(System.currentTimeMillis())
                        .total(2)
                        .sources(List.of("bing", "sogou"))
                        .sourceCounts(Map.of("bing", 1, "sogou", 1))
                        .warnings(List.of())
                        .items(List.of(
                                HotspotSearchItem.builder()
                                        .title("1688 Java 工厂技术团队介绍")
                                        .summary("阿里巴巴 1688 相关 Java 技术团队介绍")
                                        .url("https://example.com/team")
                                        .source("bing")
                                        .sourceLabel("Bing")
                                        .publishedAt(Instant.parse("2026-03-05T08:00:00Z"))
                                        .build(),
                                HotspotSearchItem.builder()
                                        .title("1688 技术岗位说明")
                                        .summary("岗位面向阿里巴巴 1688 业务")
                                        .url("https://example.com/job")
                                        .source("sogou")
                                        .sourceLabel("搜狗")
                                        .publishedAt(null)
                                        .build()
                        ))
                        .build()
        );

        BrowserMcpWebSearchService service = new BrowserMcpWebSearchService(hotspotAggregationService, hotspotAnalysisService);
        BrowserMcpWebSearchService.WebSearchResult result = service.search("1688-JAVA-工厂技术 做什么的", 5);

        assertTrue(result.success());
        assertEquals(2, result.items().size());
        String text = result.toText("1688-JAVA-工厂技术 做什么的");
        assertTrue(text.contains("可确认结论：1688 属于 阿里巴巴 相关业务或团队。")
                || text.contains("可确认结论：1688 属于 阿里巴巴 相关平台或业务。"));
        assertTrue(text.contains("标题：1688 Java 工厂技术团队介绍"));
        assertTrue(text.contains("摘要：阿里巴巴 1688 相关 Java 技术团队介绍"));
        assertTrue(text.contains("高相关归属线索：阿里巴巴 1688 相关 Java 技术团队介绍"));
        assertTrue(text.contains("来源：Bing"));
        assertTrue(text.contains("来源链接：https://example.com/team"));
        assertTrue(result.toData().toString().contains("岗位面向阿里巴巴 1688 业务"));
    }

    @Test
    void shouldPrioritizeAffiliationEvidenceForEntityIntroQuestions() {
        HotspotAggregationService hotspotAggregationService = mock(HotspotAggregationService.class);
        HotspotAnalysisService hotspotAnalysisService = mock(HotspotAnalysisService.class);
        when(hotspotAnalysisService.expandKeyword(any())).thenReturn(List.of("1688-JAVA-工厂技术", "1688 Java 工厂技术", "1688"));
        when(hotspotAggregationService.search(any(), any(), any(), anyInt())).thenReturn(
                HotspotReport.builder()
                        .query("1688-JAVA-工厂技术 做什么的")
                        .generatedAt(System.currentTimeMillis())
                        .total(3)
                        .sources(List.of("bing"))
                        .sourceCounts(Map.of("bing", 3))
                        .warnings(List.of())
                        .items(List.of(
                                HotspotSearchItem.builder()
                                        .title("1688中小企业成长中心")
                                        .summary("1688 平台官方成长内容")
                                        .url("https://club.1688.com/")
                                        .source("bing")
                                        .sourceLabel("Bing")
                                        .build(),
                                HotspotSearchItem.builder()
                                        .title("1688是个什么样的平台? - 知乎")
                                        .summary("马云于1999年创办了阿里巴巴网站，即1688的前身。1688现为阿里集团的旗舰业务。")
                                        .url("https://www.zhihu.com/question/482454010")
                                        .source("bing")
                                        .sourceLabel("Bing")
                                        .build(),
                                HotspotSearchItem.builder()
                                        .title("1688 Java 工厂技术岗位说明")
                                        .summary("面向 1688 业务的 Java 技术岗位介绍")
                                        .url("https://example.com/job")
                                        .source("bing")
                                        .sourceLabel("Bing")
                                        .build()
                        ))
                        .build()
        );

        BrowserMcpWebSearchService service = new BrowserMcpWebSearchService(hotspotAggregationService, hotspotAnalysisService);
        BrowserMcpWebSearchService.WebSearchResult result = service.search("1688-JAVA-工厂技术 做什么的", 3);

        assertTrue(result.success());
        assertEquals("1688是个什么样的平台? - 知乎", result.items().get(0).title());
        assertTrue(result.items().get(0).summary().contains("阿里巴巴"));
    }
}
