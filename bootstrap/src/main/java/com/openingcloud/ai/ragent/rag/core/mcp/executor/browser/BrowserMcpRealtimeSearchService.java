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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAggregationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSource;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实时信息联网搜索服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserMcpRealtimeSearchService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final String WEATHER_DOC_URL = "https://open-meteo.com/en/docs";
    private static final List<String> WEATHER_HINTS = List.of(
            "天气", "气温", "温度", "降雨", "下雨", "降雪", "台风", "空气质量", "湿度", "风力"
    );
    private static final List<String> MARKET_HINTS = List.of(
            "股价", "汇率", "币价", "油价", "金价", "票房", "比分", "赛程", "行情", "价格", "涨跌"
    );
    private static final List<String> TRUSTED_DOMAIN_HINTS = List.of(
            "weather.com.cn", "nmc.cn", "cma.gov.cn", "timeanddate.com",
            "investing.com", "xueqiu.com", "eastmoney.com", "finance.sina.com.cn"
    );
    private static final java.util.regex.Pattern WEATHER_LOCATION_PATTERN = java.util.regex.Pattern.compile(
            "([\\p{IsHan}A-Za-z·\\s]{1,20}?)(?:天气|气温|温度|空气质量|湿度|风力|降雨|下雨|降雪)"
    );

    private final HotspotAggregationService hotspotAggregationService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public RealtimeSearchResult search(String query, int limit) {
        RealtimeSearchResult weatherResult = searchWeatherWithApi(query);
        if (weatherResult != null) {
            return weatherResult;
        }
        List<String> expandedQueries = buildExpandedQueries(query);
        HotspotReport report = hotspotAggregationService.search(
                query,
                expandedQueries,
                List.of(
                        HotspotSource.BING,
                        HotspotSource.BAIDU,
                        HotspotSource.SOGOU,
                        HotspotSource.DUCKDUCKGO
                ),
                Math.max(3, Math.min(limit + 2, 8))
        );

        if (report == null || report.getItems() == null || report.getItems().isEmpty()) {
            List<String> warnings = report == null || report.getWarnings() == null ? List.of() : report.getWarnings();
            String warning = warnings.isEmpty()
                    ? "未检索到可用的实时信息，请稍后再试。"
                    : String.join("；", warnings);
            return RealtimeSearchResult.failed(warning);
        }

        List<RealtimeItem> items = rerankItems(query, report.getItems()).stream()
                .limit(Math.max(1, Math.min(limit, 5)))
                .map(this::toRealtimeItem)
                .toList();
        return RealtimeSearchResult.success(items);
    }

    private RealtimeSearchResult searchWeatherWithApi(String query) {
        if (!containsAny(query, WEATHER_HINTS)) {
            return null;
        }
        String location = extractWeatherLocation(query);
        if (StrUtil.isBlank(location)) {
            return null;
        }
        try {
            JsonObject geoJson = fetchJson(
                    "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=zh&format=json"
                            .formatted(encode(location))
            );
            JsonArray geoResults = geoJson == null ? null : geoJson.getAsJsonArray("results");
            if (geoResults == null || geoResults.isEmpty()) {
                return null;
            }
            JsonObject geo = geoResults.get(0).getAsJsonObject();
            double latitude = geo.get("latitude").getAsDouble();
            double longitude = geo.get("longitude").getAsDouble();
            String timezone = getString(geo, "timezone", "Asia/Shanghai");
            String forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"
                    + "&timezone=%s";
            forecastUrl = forecastUrl.formatted(latitude, longitude, encode(timezone));
            JsonObject weatherJson = fetchJson(forecastUrl);
            JsonObject current = weatherJson == null ? null : weatherJson.getAsJsonObject("current");
            if (current == null) {
                return null;
            }
            String displayName = buildDisplayName(geo);
            String observationTime = getString(current, "time", "");
            String date = observationTime.length() >= 10 ? observationTime.substring(0, 10) : "-";
            RealtimeItem item = new RealtimeItem(
                    displayName + " 当前天气",
                    buildWeatherSummary(displayName, current, timezone),
                    WEATHER_DOC_URL,
                    date,
                    "Open-Meteo"
            );
            return RealtimeSearchResult.success(List.of(item));
        } catch (Exception e) {
            log.warn("Open-Meteo 天气查询失败, query={}", query, e);
            return null;
        }
    }

    private List<String> buildExpandedQueries(String query) {
        String normalized = StrUtil.blankToDefault(query, "实时信息").trim();
        Set<String> expanded = new LinkedHashSet<>();
        expanded.add(normalized);

        if (containsAny(normalized, WEATHER_HINTS)) {
            expanded.add(normalized + " 预报");
            expanded.add(normalized + " 官方");
            expanded.add(normalized + " 实时");
        } else if (containsAny(normalized, MARKET_HINTS)) {
            expanded.add(normalized + " 实时行情");
            expanded.add(normalized + " 最新价格");
            expanded.add(normalized + " 官方");
        } else {
            expanded.add(normalized + " 最新");
            expanded.add(normalized + " 实时");
            expanded.add(normalized + " 官方");
        }
        return List.copyOf(expanded);
    }

    private List<HotspotSearchItem> rerankItems(String query, List<HotspotSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String normalized = StrUtil.blankToDefault(query, "").toLowerCase(Locale.ROOT);
        List<String> tokens = extractTokens(query);
        return items.stream()
                .sorted((left, right) -> Double.compare(
                        scoreItem(right, normalized, tokens),
                        scoreItem(left, normalized, tokens)
                ))
                .toList();
    }

    private double scoreItem(HotspotSearchItem item, String normalizedQuery, List<String> tokens) {
        String title = StrUtil.blankToDefault(item.getTitle(), "");
        String summary = StrUtil.blankToDefault(item.getSummary(), "");
        String url = StrUtil.blankToDefault(item.getUrl(), "");
        String combined = (title + " " + summary + " " + url).toLowerCase(Locale.ROOT);
        double score = 0D;
        if (StrUtil.isNotBlank(normalizedQuery) && combined.contains(normalizedQuery)) {
            score += 8D;
        }
        for (String token : tokens) {
            if (token.length() >= 2 && combined.contains(token.toLowerCase(Locale.ROOT))) {
                score += token.length() >= 4 ? 1.2D : 0.6D;
            }
        }
        if (containsAny(combined, WEATHER_HINTS)) {
            score += 1.4D;
        }
        if (containsAny(combined, MARKET_HINTS)) {
            score += 1.4D;
        }
        for (String trustedDomain : TRUSTED_DOMAIN_HINTS) {
            if (url.contains(trustedDomain)) {
                score += 1.8D;
            }
        }
        if (item.getPublishedAt() != null) {
            score += 0.5D;
        }
        return score;
    }

    private List<String> extractTokens(String query) {
        String normalized = StrUtil.blankToDefault(query, "")
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            String one = StrUtil.trimToEmpty(token);
            if (one.length() >= 2) {
                tokens.add(one);
            }
        }
        return tokens;
    }

    private boolean containsAny(String text, List<String> candidates) {
        String normalized = StrUtil.blankToDefault(text, "").toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String extractWeatherLocation(String query) {
        String normalized = StrUtil.blankToDefault(query, "")
                .replaceAll("(今天|今日|当前|现在|最新|实时|明天|本周|怎么样|如何|多少|是多少|情况)", " ")
                .replace('？', ' ')
                .replace('?', ' ')
                .trim();
        java.util.regex.Matcher matcher = WEATHER_LOCATION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        String location = StrUtil.trim(matcher.group(1));
        location = location.replaceAll("^(请帮我|帮我|请问|查一下|查一查|搜一下|搜一搜)\\s*", "");
        location = location.replaceAll("\\s+", "");
        location = location.replaceAll("的+$", "");
        return StrUtil.blankToDefault(location, null);
    }

    private JsonObject fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300 || StrUtil.isBlank(response.body())) {
            return null;
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String buildDisplayName(JsonObject geo) {
        String name = getString(geo, "name", "目标地区");
        String admin1 = getString(geo, "admin1", "");
        if (StrUtil.isBlank(admin1) || StrUtil.equals(name, admin1)) {
            return name;
        }
        return name + " " + admin1;
    }

    private String buildWeatherSummary(String displayName, JsonObject current, String timezone) {
        double temperature = getDouble(current, "temperature_2m");
        double apparentTemperature = getDouble(current, "apparent_temperature");
        double humidity = getDouble(current, "relative_humidity_2m");
        double precipitation = getDouble(current, "precipitation");
        double windSpeed = getDouble(current, "wind_speed_10m");
        int weatherCode = getInt(current, "weather_code");
        String observationTime = getString(current, "time", "-");
        return "%s当前%s，%.1f°C，体感%.1f°C，湿度%.0f%%，风速%.1fkm/h，降水%.2fmm，观测时间%s（%s）。"
                .formatted(
                        displayName,
                        weatherCodeToText(weatherCode),
                        temperature,
                        apparentTemperature,
                        humidity,
                        windSpeed,
                        precipitation,
                        observationTime,
                        timezone
                );
    }

    private String weatherCodeToText(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2, 3 -> "多云";
            case 45, 48 -> "雾";
            case 51, 53, 55, 56, 57 -> "毛毛雨";
            case 61, 63, 65, 66, 67, 80, 81, 82 -> "降雨";
            case 71, 73, 75, 77, 85, 86 -> "降雪";
            case 95, 96, 99 -> "雷暴";
            default -> "天气状态码 " + code;
        };
    }

    private String getString(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return StrUtil.blankToDefault(json.get(key).getAsString(), fallback);
    }

    private double getDouble(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0D;
        }
        return json.get(key).getAsDouble();
    }

    private int getInt(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        return json.get(key).getAsInt();
    }

    private String encode(String value) {
        return URLEncoder.encode(StrUtil.blankToDefault(value, ""), StandardCharsets.UTF_8);
    }

    private RealtimeItem toRealtimeItem(HotspotSearchItem item) {
        String date = item.getPublishedAt() == null ? "-" : DATE_FORMATTER.format(item.getPublishedAt());
        return new RealtimeItem(
                item.getTitle(),
                item.getSummary(),
                item.getUrl(),
                date,
                StrUtil.blankToDefault(item.getSourceLabel(), item.getSource())
        );
    }

    @Builder
    public record RealtimeSearchResult(boolean success,
                                       String message,
                                       List<RealtimeItem> items) {

        public static RealtimeSearchResult success(List<RealtimeItem> items) {
            return RealtimeSearchResult.builder()
                    .success(true)
                    .items(items)
                    .build();
        }

        public static RealtimeSearchResult failed(String message) {
            return RealtimeSearchResult.builder()
                    .success(false)
                    .message(message)
                    .items(List.of())
                    .build();
        }

        public String toText(String query) {
            if (!success || items == null || items.isEmpty()) {
                return StrUtil.blankToDefault(message, "联网实时信息检索失败。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("联网实时信息结果（关键词：").append(StrUtil.blankToDefault(query, "实时信息")).append("）：\n");
            for (int i = 0; i < items.size(); i++) {
                RealtimeItem item = items.get(i);
                sb.append(i + 1).append(". 标题：").append(item.title()).append("\n");
                if (StrUtil.isNotBlank(item.summary())) {
                    sb.append("   摘要：").append(item.summary()).append("\n");
                }
                sb.append("   来源：").append(item.source()).append("\n");
                sb.append("   来源链接：").append(item.url()).append("\n");
                sb.append("   发布日期：").append(item.date()).append("\n");
            }
            return sb.toString().trim();
        }

        public Map<String, Object> toData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", message);
            List<Map<String, String>> normalizedItems = new ArrayList<>();
            if (items != null) {
                for (RealtimeItem item : items) {
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

    public record RealtimeItem(String title, String summary, String url, String date, String source) {
    }
}
