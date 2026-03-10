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

package com.openingcloud.ai.ragent.rag.core.mcp.executor.browser;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAggregationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAnalysisService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSource;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * 通用联网网页搜索服务。
 */
@Service
@RequiredArgsConstructor
public class BrowserMcpWebSearchService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final List<String> ENTITY_INTRO_SUFFIXES = List.of(
            "是做什么的", "做什么的", "是干什么的", "干什么的", "是什么", "属于哪个公司", "属于谁", "属于哪个业务",
            "是哪个业务", "哪个业务", "什么业务", "什么平台", "平台介绍", "团队介绍", "岗位介绍", "介绍",
            "功能", "作用", "职责", "职能"
    );
    private static final List<String> AFFILIATION_HINTS = List.of(
            "属于", "旗下", "集团", "平台", "业务", "团队", "官方", "介绍", "前身"
    );
    private static final List<String> AFFILIATION_ORG_HINTS = List.of(
            "阿里巴巴", "阿里集团", "腾讯", "字节跳动", "蚂蚁集团", "百度", "京东", "美团", "拼多多", "华为", "小红书"
    );

    private final HotspotAggregationService hotspotAggregationService;
    private final HotspotAnalysisService hotspotAnalysisService;

    public WebSearchResult search(String query, int limit) {
        List<String> expandedQueries = buildExpandedQueries(query);
        HotspotReport report = hotspotAggregationService.search(
                query,
                expandedQueries,
                List.of(
                        HotspotSource.BING,
                        HotspotSource.SOGOU,
                        HotspotSource.DUCKDUCKGO
                ),
                Math.max(3, Math.min(limit + 2, 8))
        );

        if (report.getItems() == null || report.getItems().isEmpty()) {
            String warning = report.getWarnings().isEmpty()
                    ? "未检索到可用的网页结果，请稍后再试。"
                    : String.join("；", report.getWarnings());
            return WebSearchResult.failed(warning);
        }

        List<HotspotSearchItem> rerankedItems = rerankEntityIntroItems(query, report.getItems(), expandedQueries);
        List<WebItem> items = rerankedItems.stream()
                .limit(Math.max(1, Math.min(limit, 5)))
                .map(this::toWebItem)
                .toList();
        String affiliationHint = extractAffiliationHint(query, rerankedItems);
        return WebSearchResult.success(items, buildAffiliationConclusion(query, affiliationHint), affiliationHint);
    }

    private List<String> buildExpandedQueries(String query) {
        String normalizedQuery = StrUtil.blankToDefault(query, "AI").trim();
        Set<String> expanded = new LinkedHashSet<>(hotspotAnalysisService.expandKeyword(normalizedQuery));
        String entity = extractEntityCandidate(normalizedQuery);
        if (StrUtil.isNotBlank(entity)) {
            expanded.add(entity);
            hotspotAnalysisService.expandKeyword(entity).forEach(expanded::add);
            String root = extractPrimaryToken(entity);
            if (StrUtil.isNotBlank(root)) {
                expanded.add(root + " 属于哪个公司");
                expanded.add(root + " 是什么平台");
                expanded.add(root + " 团队介绍");
            }
            expanded.add(entity + " 属于哪个公司");
            expanded.add(entity + " 是什么平台");
            expanded.add(entity + " 业务介绍");
            expanded.add(entity + " 团队介绍");
        }
        return List.copyOf(expanded);
    }

    private List<HotspotSearchItem> rerankEntityIntroItems(String query,
                                                           List<HotspotSearchItem> items,
                                                           List<String> expandedQueries) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String entity = extractEntityCandidate(query);
        String root = extractPrimaryToken(entity);
        return items.stream()
                .sorted((left, right) -> Double.compare(
                        scoreItemForEntityIntro(right, entity, root, expandedQueries),
                        scoreItemForEntityIntro(left, entity, root, expandedQueries)
                ))
                .toList();
    }

    private double scoreItemForEntityIntro(HotspotSearchItem item,
                                           String entity,
                                           String root,
                                           List<String> expandedQueries) {
        String title = StrUtil.blankToDefault(item.getTitle(), "");
        String summary = StrUtil.blankToDefault(item.getSummary(), "");
        String url = StrUtil.blankToDefault(item.getUrl(), "");
        String combined = (title + " " + summary + " " + url).toLowerCase(Locale.ROOT);
        double score = 0D;
        if (StrUtil.isNotBlank(entity) && combined.contains(entity.toLowerCase(Locale.ROOT))) {
            score += 8D;
        }
        if (StrUtil.isNotBlank(root) && combined.contains(root.toLowerCase(Locale.ROOT))) {
            score += 4D;
        }
        for (String hint : AFFILIATION_HINTS) {
            if (combined.contains(hint.toLowerCase(Locale.ROOT))) {
                score += 1.4D;
            }
        }
        for (String expanded : expandedQueries) {
            String normalizedExpanded = StrUtil.trimToEmpty(expanded).toLowerCase(Locale.ROOT);
            if (normalizedExpanded.length() >= 2 && combined.contains(normalizedExpanded)) {
                score += normalizedExpanded.length() >= 6 ? 1.2D : 0.5D;
            }
        }
        if (title.contains("知乎") || url.contains("zhihu.com")) {
            score += 0.6D;
        }
        if (url.contains("1688.com")) {
            score += 0.8D;
        }
        return score;
    }

    private String extractEntityCandidate(String query) {
        String normalized = StrUtil.blankToDefault(query, "").trim();
        for (String suffix : ENTITY_INTRO_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                normalized = StrUtil.removeSuffix(normalized, suffix).trim();
                break;
            }
        }
        normalized = normalized.replaceAll("[?？!！。；;，,]+$", "").trim();
        return normalized;
    }

    private String extractPrimaryToken(String entity) {
        if (StrUtil.isBlank(entity)) {
            return null;
        }
        for (String token : entity.split("[\\s\\-_\\\\/·]+")) {
            String normalized = StrUtil.trimToNull(token);
            if (normalized != null && normalized.length() >= 2) {
                return normalized;
            }
        }
        return StrUtil.trimToNull(entity);
    }

    private String extractAffiliationHint(String query, List<HotspotSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        String entity = extractEntityCandidate(query);
        String root = extractPrimaryToken(entity);
        for (HotspotSearchItem item : items) {
            String summary = StrUtil.blankToDefault(item.getSummary(), "");
            String title = StrUtil.blankToDefault(item.getTitle(), "");
            String combined = (title + " " + summary).replaceAll("\\s+", " ").trim();
            boolean containsEntity = StrUtil.isBlank(root) || combined.toLowerCase(Locale.ROOT).contains(root.toLowerCase(Locale.ROOT));
            boolean containsAffiliation = AFFILIATION_HINTS.stream().anyMatch(combined::contains)
                    || AFFILIATION_ORG_HINTS.stream().anyMatch(combined::contains);
            if (!containsEntity || !containsAffiliation) {
                continue;
            }
            String summarySentence = firstSentence(summary);
            String evidence = StrUtil.blankToDefault(summarySentence, title);
            return evidence + " 来源：" + StrUtil.blankToDefault(item.getSourceLabel(), item.getSource())
                    + " " + StrUtil.blankToDefault(item.getUrl(), "-");
        }
        return null;
    }

    private String buildAffiliationConclusion(String query, String affiliationHint) {
        if (StrUtil.isBlank(affiliationHint)) {
            return null;
        }
        String entity = extractEntityCandidate(query);
        String root = StrUtil.blankToDefault(extractPrimaryToken(entity), entity);
        String organization = AFFILIATION_ORG_HINTS.stream()
                .filter(affiliationHint::contains)
                .findFirst()
                .orElseGet(() -> inferOrganizationFromHint(affiliationHint));
        if (StrUtil.isBlank(root) || StrUtil.isBlank(organization)) {
            return null;
        }
        if (affiliationHint.contains("平台")) {
            return root + " 属于 " + organization + " 相关平台或业务。";
        }
        return root + " 属于 " + organization + " 相关业务或团队。";
    }

    private String inferOrganizationFromHint(String affiliationHint) {
        String normalized = StrUtil.blankToDefault(affiliationHint, "");
        int index = normalized.indexOf("旗下");
        if (index > 1) {
            return normalized.substring(Math.max(0, index - 8), index).replaceAll("^[^\\p{IsHan}A-Za-z0-9]+", "").trim();
        }
        index = normalized.indexOf("集团");
        if (index >= 1) {
            int start = Math.max(0, index - 8);
            return normalized.substring(start, Math.min(normalized.length(), index + 2))
                    .replaceAll("^[^\\p{IsHan}A-Za-z0-9]+", "")
                    .trim();
        }
        index = normalized.indexOf("公司");
        if (index >= 1) {
            int start = Math.max(0, index - 8);
            return normalized.substring(start, Math.min(normalized.length(), index + 2))
                    .replaceAll("^[^\\p{IsHan}A-Za-z0-9]+", "")
                    .trim();
        }
        return null;
    }

    private String firstSentence(String text) {
        String normalized = StrUtil.trimToNull(text);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split("[。！？!?]");
        for (String part : parts) {
            String sentence = StrUtil.trimToNull(part);
            if (sentence != null) {
                return sentence;
            }
        }
        return normalized;
    }

    private WebItem toWebItem(HotspotSearchItem item) {
        String date = item.getPublishedAt() == null ? null : DATE_FORMATTER.format(item.getPublishedAt());
        return new WebItem(
                item.getTitle(),
                StrUtil.blankToDefault(item.getSummary(), "暂无摘要"),
                item.getUrl(),
                date,
                StrUtil.blankToDefault(item.getSourceLabel(), item.getSource())
        );
    }

    @Builder
    public record WebSearchResult(boolean success,
                                  String message,
                                  String confirmedConclusion,
                                  String affiliationHint,
                                  List<WebItem> items) {

        public static WebSearchResult success(List<WebItem> items) {
            return success(items, null, null);
        }

        public static WebSearchResult success(List<WebItem> items, String confirmedConclusion, String affiliationHint) {
            return WebSearchResult.builder()
                    .success(true)
                    .confirmedConclusion(confirmedConclusion)
                    .affiliationHint(affiliationHint)
                    .items(items)
                    .build();
        }

        public static WebSearchResult failed(String message) {
            return WebSearchResult.builder()
                    .success(false)
                    .message(message)
                    .items(List.of())
                    .build();
        }

        public String toText(String query) {
            if (!success || items == null || items.isEmpty()) {
                return StrUtil.blankToDefault(message, "联网网页检索失败。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("联网网页检索结果（关键词：").append(StrUtil.blankToDefault(query, "未指定")).append("）：\n");
            if (StrUtil.isNotBlank(confirmedConclusion)) {
                sb.append("可确认结论：").append(confirmedConclusion).append("\n");
            }
            if (StrUtil.isNotBlank(affiliationHint)) {
                sb.append("高相关归属线索：").append(affiliationHint).append("\n");
            }
            for (int i = 0; i < items.size(); i++) {
                WebItem item = items.get(i);
                sb.append(i + 1).append(". 标题：").append(item.title()).append("\n");
                sb.append("   摘要：").append(StrUtil.blankToDefault(item.summary(), "暂无摘要")).append("\n");
                sb.append("   来源：").append(StrUtil.blankToDefault(item.source(), "-")).append("\n");
                sb.append("   来源链接：").append(StrUtil.blankToDefault(item.url(), "-")).append("\n");
                if (StrUtil.isNotBlank(item.date())) {
                    sb.append("   发布日期：").append(item.date()).append("\n");
                }
            }
            return sb.toString().trim();
        }

        public Map<String, Object> toData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", message);
            data.put("confirmedConclusion", confirmedConclusion);
            data.put("affiliationHint", affiliationHint);
            List<Map<String, String>> normalizedItems = new ArrayList<>();
            if (items != null) {
                for (WebItem item : items) {
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("title", item.title());
                    one.put("summary", item.summary());
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

    public record WebItem(String title, String summary, String url, String date, String source) {
    }
}
