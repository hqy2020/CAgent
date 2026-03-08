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

package com.nageoffer.ai.ragent.rag.hotspot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.config.HotspotProperties;
import com.nageoffer.ai.ragent.rag.core.hotspot.HotspotAnalysisService;
import com.nageoffer.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HotspotAnalysisServiceTests {

    @Test
    void shouldFallbackToCoreTermsWhenKeywordExpansionFails() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM unavailable"));
        HotspotProperties properties = new HotspotProperties();
        properties.setAnalysisEnabled(true);

        HotspotAnalysisService service = new HotspotAnalysisService(new ObjectMapper(), llmService, properties);
        List<String> expanded = service.expandKeyword("Claude Sonnet 4.6");

        assertTrue(expanded.contains("Claude Sonnet 4.6"));
        assertTrue(expanded.stream().anyMatch(item -> item.contains("Claude")));
        assertTrue(expanded.stream().anyMatch(item -> item.contains("Sonnet")));
    }

    @Test
    void shouldApplyHeuristicAnalysisWhenAiFails() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM unavailable"));
        HotspotProperties properties = new HotspotProperties();
        properties.setAnalysisEnabled(true);
        properties.setAnalysisTopN(5);

        HotspotAnalysisService service = new HotspotAnalysisService(new ObjectMapper(), llmService, properties);
        HotspotSearchItem item = HotspotSearchItem.builder()
                .title("OpenAI 发布 GPT 新模型更新")
                .summary("OpenAI 在今天公布了新的 GPT 版本和 API 升级。")
                .url("https://example.com/openai-gpt")
                .source("twitter")
                .sourceLabel("Twitter")
                .build();

        List<HotspotSearchItem> analyzed = service.analyzeItems("OpenAI", List.of("OpenAI", "GPT"), List.of(item));

        assertFalse(analyzed.isEmpty());
        HotspotSearchItem result = analyzed.get(0);
        assertNotNull(result.getRelevanceScore());
        assertNotNull(result.getCredibilityScore());
        assertTrue(result.getRelevanceScore() >= 0.6D);
        assertNotNull(result.getVerdict());
        assertNotNull(result.getMatchedKeywords());
        assertFalse(result.getMatchedKeywords().isEmpty());
        assertNotNull(result.getAnalysisSummary());
    }
}
