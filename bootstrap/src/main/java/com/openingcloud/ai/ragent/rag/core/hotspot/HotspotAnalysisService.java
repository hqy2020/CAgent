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

package com.openingcloud.ai.ragent.rag.core.hotspot;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.config.HotspotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控关键词扩展与 AI 真假/相关性分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotspotAnalysisService {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;
    private final HotspotProperties hotspotProperties;

    private final Map<String, List<String>> expansionCache = new ConcurrentHashMap<>();

    public List<String> expandKeyword(String keyword) {
        String normalized = StrUtil.blankToDefault(keyword, "AI").trim();
        return expansionCache.computeIfAbsent(normalized, this::expandKeywordInternal);
    }

    public HotspotReport analyzeReport(String keyword, HotspotReport report) {
        List<String> expandedKeywords = report.getExpandedQueries() == null || report.getExpandedQueries().isEmpty()
                ? expandKeyword(keyword)
                : report.getExpandedQueries();
        List<HotspotSearchItem> analyzedItems = analyzeItems(keyword, expandedKeywords, report.getItems());
        return HotspotReport.builder()
                .query(report.getQuery())
                .generatedAt(report.getGeneratedAt())
                .total(analyzedItems.size())
                .sources(report.getSources())
                .sourceCounts(report.getSourceCounts())
                .warnings(report.getWarnings())
                .expandedQueries(expandedKeywords)
                .analyzed(true)
                .items(analyzedItems)
                .build();
    }

    public List<HotspotSearchItem> analyzeItems(String keyword,
                                                Collection<String> expandedKeywords,
                                                List<HotspotSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> normalizedKeywords = normalizeExpandedKeywords(keyword, expandedKeywords);
        Map<String, AnalysisResult> aiResults = hotspotProperties.isAnalysisEnabled()
                ? runBatchAnalysis(keyword, normalizedKeywords, items)
                : Map.of();
        List<HotspotSearchItem> analyzed = new ArrayList<>(items.size());
        int topN = Math.max(1, hotspotProperties.getAnalysisTopN());
        for (int index = 0; index < items.size(); index++) {
            HotspotSearchItem item = items.get(index);
            AnalysisResult result = aiResults.getOrDefault(item.getUrl(), heuristicAnalyze(keyword, normalizedKeywords, item));
            if (index >= topN && !aiResults.containsKey(item.getUrl())) {
                result = heuristicAnalyze(keyword, normalizedKeywords, item);
            }
            analyzed.add(copyWithAnalysis(item, result));
        }
        return analyzed;
    }

    private List<String> expandKeywordInternal(String keyword) {
        List<String> fallback = normalizeExpandedKeywords(keyword, extractCoreTerms(keyword));
        if (!hotspotProperties.isAnalysisEnabled()) {
            return fallback;
        }
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system("""
                                    你是一个搜索关键词扩展专家。
                                    给定监控关键词，输出 JSON 数组，只包含适合搜索和文本匹配的 5-12 个变体。
                                    规则：
                                    1. 必须包含原词、大小写/连字符/空格变体、核心拆分词。
                                    2. 可以包含常见别名、中英文别称、产品简称。
                                    3. 不要加入泛化领域词，不要偏题。
                                    4. 只能输出 JSON 数组。
                                    """),
                            ChatMessage.user(keyword)
                    ))
                    .temperature(0.2)
                    .maxTokens(300)
                    .build();
            String raw = llmService.chat(request);
            String json = extractJsonArray(raw);
            if (json == null) {
                return fallback;
            }
            List<String> parsed = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return normalizeExpandedKeywords(keyword, parsed);
        } catch (Exception ex) {
            log.debug("Hotspot keyword expansion fallback: keyword={}", keyword, ex);
            return fallback;
        }
    }

    private Map<String, AnalysisResult> runBatchAnalysis(String keyword,
                                                         List<String> expandedKeywords,
                                                         List<HotspotSearchItem> items) {
        try {
            List<Map<String, Object>> payload = items.stream()
                    .limit(Math.max(1, hotspotProperties.getAnalysisTopN()))
                    .map(item -> Map.<String, Object>of(
                            "title", item.getTitle(),
                            "summary", StrUtil.blankToDefault(item.getSummary(), item.getTitle()),
                            "url", item.getUrl(),
                            "source", item.getSource(),
                            "publishedAt", item.getPublishedAt() == null ? "" : item.getPublishedAt().toString()
                    ))
                    .toList();
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system("""
                                    你是热点监控审核助手。请根据指定监控关键词，判断每条内容的真假风险和相关性。
                                    输出 JSON 数组，每项字段如下：
                                    {
                                      "url": "原始 URL",
                                      "relevanceScore": 0-1,
                                      "credibilityScore": 0-1,
                                      "verdict": "较可信/待核验/高风险/弱相关",
                                      "summary": "一句话说明与关键词的关系",
                                      "reason": "给分依据",
                                      "matchedKeywords": ["命中的关键词"]
                                    }
                                    评分规则：
                                    1. 内容必须直接提及或实质围绕关键词，relevanceScore 才能 >= 0.6。
                                    2. 搜索结果页、转载、营销软文的 credibilityScore 不能过高。
                                    3. 不能确定真实性时输出“待核验”，明显蹭热点或错配时输出“弱相关”或“高风险”。
                                    4. 只能输出 JSON 数组。
                                    """),
                            ChatMessage.user("关键词: " + keyword
                                    + "\n扩展词: " + String.join(", ", expandedKeywords)
                                    + "\n待分析条目:\n" + objectMapper.writeValueAsString(payload))
                    ))
                    .temperature(0.2)
                    .maxTokens(1600)
                    .build();
            String raw = llmService.chat(request);
            String json = extractJsonArray(raw);
            if (json == null) {
                return Map.of();
            }
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return Map.of();
            }
            Map<String, AnalysisResult> results = new LinkedHashMap<>();
            for (JsonNode node : array) {
                String url = StrUtil.trimToNull(node.path("url").asText(""));
                if (url == null) {
                    continue;
                }
                double relevance = clampScore(node.path("relevanceScore").asDouble(0.0));
                double credibility = clampScore(node.path("credibilityScore").asDouble(0.0));
                List<String> matched = new ArrayList<>();
                JsonNode matchedNode = node.path("matchedKeywords");
                if (matchedNode.isArray()) {
                    matchedNode.forEach(item -> {
                        String value = StrUtil.trimToNull(item.asText(""));
                        if (value != null) {
                            matched.add(value);
                        }
                    });
                }
                results.put(url, new AnalysisResult(
                        relevance,
                        credibility,
                        StrUtil.blankToDefault(node.path("verdict").asText(""), defaultVerdict(relevance, credibility)),
                        StrUtil.blankToDefault(node.path("summary").asText(""), ""),
                        StrUtil.blankToDefault(node.path("reason").asText(""), ""),
                        matched
                ));
            }
            return results;
        } catch (Exception ex) {
            log.debug("Hotspot batch analysis fallback: keyword={}", keyword, ex);
            return Map.of();
        }
    }

    private HotspotSearchItem copyWithAnalysis(HotspotSearchItem item, AnalysisResult result) {
        return HotspotSearchItem.builder()
                .title(item.getTitle())
                .summary(item.getSummary())
                .url(item.getUrl())
                .source(item.getSource())
                .sourceLabel(item.getSourceLabel())
                .publishedAt(item.getPublishedAt())
                .hotScore(item.getHotScore())
                .viewCount(item.getViewCount())
                .likeCount(item.getLikeCount())
                .commentCount(item.getCommentCount())
                .score(item.getScore())
                .danmakuCount(item.getDanmakuCount())
                .authorName(item.getAuthorName())
                .authorUsername(item.getAuthorUsername())
                .relevanceScore(result.relevanceScore())
                .credibilityScore(result.credibilityScore())
                .verdict(result.verdict())
                .analysisSummary(result.summary())
                .analysisReason(result.reason())
                .matchedKeywords(result.matchedKeywords())
                .build();
    }

    private AnalysisResult heuristicAnalyze(String keyword,
                                            List<String> expandedKeywords,
                                            HotspotSearchItem item) {
        String combined = (StrUtil.blankToDefault(item.getTitle(), "")
                + " "
                + StrUtil.blankToDefault(item.getSummary(), ""))
                .toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String token : expandedKeywords) {
            if (combined.contains(token.toLowerCase(Locale.ROOT))) {
                matched.add(token);
            }
        }
        double relevance;
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        if (combined.contains(normalizedKeyword)) {
            relevance = 0.88D;
        } else if (!matched.isEmpty()) {
            relevance = Math.min(0.75D, 0.35D + matched.size() * 0.12D);
        } else {
            relevance = 0.18D;
        }
        double credibility = switch (StrUtil.blankToDefault(item.getSource(), "")) {
            case "twitter", "weibo" -> 0.45D;
            case "bilibili" -> 0.55D;
            case "bing", "baidu", "duckduckgo", "sogou" -> 0.62D;
            case "hackernews", "reddit" -> 0.72D;
            default -> 0.58D;
        };
        if (item.getViewCount() != null && item.getViewCount() > 10000) {
            credibility = Math.min(0.95D, credibility + 0.08D);
        }
        if (StrUtil.isBlank(item.getSummary())) {
            credibility = Math.max(0.3D, credibility - 0.08D);
        }
        String verdict = defaultVerdict(relevance, credibility);
        String summary = relevance >= 0.6D
                ? "内容与关键词直接相关，可进入持续跟踪。"
                : "内容与关键词关联较弱，建议仅作为辅助线索。";
        String reason = "启发式评估：命中关键词 " + (matched.isEmpty() ? "0" : matched.size())
                + " 次，来源 " + item.getSourceLabel() + " 的基础可信度为 " + formatScore(credibility) + "。";
        return new AnalysisResult(relevance, credibility, verdict, summary, reason, matched);
    }

    private List<String> extractCoreTerms(String keyword) {
        Set<String> terms = new LinkedHashSet<>();
        String[] parts = keyword.split("[\\s\\-_\\\\/·]+");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part != null && part.length() >= 2) {
                normalized.add(part.trim());
            }
        }
        terms.add(keyword);
        terms.addAll(normalized);
        for (int i = 0; i < normalized.size() - 1; i++) {
            terms.add(normalized.get(i) + " " + normalized.get(i + 1));
            terms.add(normalized.get(i) + "-" + normalized.get(i + 1));
        }
        return new ArrayList<>(terms);
    }

    private List<String> normalizeExpandedKeywords(String keyword, Collection<String> expandedKeywords) {
        Set<String> normalized = new LinkedHashSet<>();
        normalized.add(StrUtil.blankToDefault(keyword, "AI").trim());
        if (expandedKeywords != null) {
            for (String item : expandedKeywords) {
                String value = StrUtil.trimToNull(item);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private String extractJsonArray(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private double clampScore(double value) {
        if (Double.isNaN(value)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, value));
    }

    private String defaultVerdict(double relevance, double credibility) {
        if (relevance < 0.45D) {
            return "弱相关";
        }
        if (credibility < 0.4D) {
            return "高风险";
        }
        if (credibility >= 0.72D) {
            return "较可信";
        }
        return "待核验";
    }

    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record AnalysisResult(double relevanceScore,
                                  double credibilityScore,
                                  String verdict,
                                  String summary,
                                  String reason,
                                  List<String> matchedKeywords) {
    }
}
