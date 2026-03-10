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

package com.openingcloud.ai.ragent.rag.hotspot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.admin.service.HotspotMonitorService;
import com.openingcloud.ai.ragent.rag.config.HotspotProperties;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAggregationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAnalysisService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotNotificationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSource;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorDO;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorEventDO;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorRunDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.HotspotMonitorEventMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.HotspotMonitorMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.HotspotMonitorRunMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotspotMonitorServiceTests {

    @Test
    void shouldRequireCredibilityThresholdAndKeepWarningsOutOfLastError() {
        HotspotMonitorMapper monitorMapper = mock(HotspotMonitorMapper.class);
        HotspotMonitorEventMapper eventMapper = mock(HotspotMonitorEventMapper.class);
        HotspotMonitorRunMapper runMapper = mock(HotspotMonitorRunMapper.class);
        HotspotAggregationService aggregationService = mock(HotspotAggregationService.class);
        HotspotAnalysisService analysisService = mock(HotspotAnalysisService.class);
        HotspotNotificationService notificationService = mock(HotspotNotificationService.class);
        HotspotProperties properties = new HotspotProperties();
        properties.setMonitorResultLimit(10);

        HotspotMonitorService service = new HotspotMonitorService(
                monitorMapper,
                eventMapper,
                runMapper,
                aggregationService,
                analysisService,
                notificationService,
                properties
        );

        HotspotMonitorDO monitor = HotspotMonitorDO.builder()
                .id(1L)
                .userId(2L)
                .keyword("OpenAI")
                .sources("twitter,bing")
                .scanIntervalMinutes(30)
                .relevanceThreshold(BigDecimal.valueOf(0.55D))
                .credibilityThreshold(BigDecimal.valueOf(0.45D))
                .websocketEnabled(1)
                .build();
        HotspotSearchItem lowCredibilityItem = HotspotSearchItem.builder()
                .title("OpenAI related rumor")
                .summary("OpenAI rumor from an unverified source.")
                .url("https://example.com/openai-rumor")
                .source(HotspotSource.TWITTER.getCode())
                .sourceLabel("Twitter")
                .publishedAt(Instant.now())
                .relevanceScore(0.92D)
                .credibilityScore(0.22D)
                .verdict("高风险")
                .build();
        HotspotReport report = HotspotReport.builder()
                .query("OpenAI")
                .generatedAt(System.currentTimeMillis())
                .total(1)
                .sources(List.of("twitter", "bing"))
                .sourceCounts(Map.of("twitter", 1))
                .warnings(List.of("Twitter API key missing"))
                .expandedQueries(List.of("OpenAI"))
                .analyzed(true)
                .items(List.of(lowCredibilityItem))
                .build();

        when(analysisService.expandKeyword("OpenAI")).thenReturn(List.of("OpenAI"));
        when(aggregationService.search(anyString(), any(), any(), anyInt())).thenReturn(report);
        when(analysisService.analyzeReport(anyString(), any())).thenReturn(report);
        when(notificationService.notifyMonitorEvents(any(), any()))
                .thenReturn(new HotspotNotificationService.NotificationResult(false, false));
        when(eventMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        HotspotMonitorRunDO run = service.executeMonitor(monitor, true);

        assertEquals("SUCCESS", run.getStatus());
        assertEquals(0, run.getQualifiedCount());
        assertEquals(0, run.getNewEventCount());
        assertEquals("Twitter API key missing", run.getWarning());
        assertEquals(0, monitor.getLastResultCount());
        assertNull(monitor.getLastError());
        assertTrue(monitor.getLastSuccessTime() != null);
        verify(eventMapper, never()).insert(org.mockito.ArgumentMatchers.<HotspotMonitorEventDO>any());
    }
}
