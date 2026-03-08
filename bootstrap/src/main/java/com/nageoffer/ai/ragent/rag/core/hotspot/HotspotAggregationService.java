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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.HotspotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多源热点抓取服务，参考 yupi-hot-monitor 的聚合抓取思路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotspotAggregationService {

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:134.0) Gecko/20100101 Firefox/134.0"
    );

    private static final Map<HotspotSource, Long> MIN_INTERVAL_MS = Map.ofEntries(
            Map.entry(HotspotSource.TWITTER, 1_500L),
            Map.entry(HotspotSource.BING, 1_200L),
            Map.entry(HotspotSource.HACKERNEWS, 500L),
            Map.entry(HotspotSource.SOGOU, 1_500L),
            Map.entry(HotspotSource.BILIBILI, 1_000L),
            Map.entry(HotspotSource.WEIBO, 1_500L),
            Map.entry(HotspotSource.BAIDU, 1_500L),
            Map.entry(HotspotSource.DUCKDUCKGO, 1_000L),
            Map.entry(HotspotSource.REDDIT, 1_000L)
    );

    private static final DateTimeFormatter TWITTER_SINCE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final HotspotProperties hotspotProperties;

    @Qualifier("ragRetrievalThreadPoolExecutor")
    private final Executor ragRetrievalThreadPoolExecutor;

    private final Map<HotspotSource, AtomicLong> rateLimitState = new ConcurrentHashMap<>();

    public HotspotReport search(String query, Collection<HotspotSource> requestedSources, int limit) {
        return search(query, List.of(query), requestedSources, limit);
    }

    public HotspotReport search(String query,
                                Collection<String> expandedQueries,
                                Collection<HotspotSource> requestedSources,
                                int limit) {
        String normalizedQuery = StrUtil.blankToDefault(query, "AI").trim();
        int boundedLimit = Math.max(1, Math.min(30, limit));
        List<HotspotSource> sources = resolveSources(requestedSources);
        List<String> queries = normalizeQueries(normalizedQuery, expandedQueries);

        if (!hotspotProperties.isEnabled()) {
            return emptyReport(normalizedQuery, sources, "热点聚合抓取已关闭。", queries);
        }
        if (sources.isEmpty()) {
            return emptyReport(normalizedQuery, List.of(), "未配置可用的热点数据源。", queries);
        }

        int perSourceLimit = Math.max(Math.min(hotspotProperties.getMaxResultsPerSource(), 20), boundedLimit);
        List<CompletableFuture<SourceFetchResult>> tasks = new ArrayList<>();
        for (HotspotSource source : sources) {
            tasks.add(CompletableFuture.supplyAsync(
                    () -> fetchBySource(queries, source, perSourceLimit),
                    ragRetrievalThreadPoolExecutor
            ));
        }

        List<HotspotSearchItem> allItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (CompletableFuture<SourceFetchResult> task : tasks) {
            SourceFetchResult result = task.join();
            allItems.addAll(result.items());
            if (StrUtil.isNotBlank(result.warning())) {
                warnings.add(result.warning());
            }
        }

        Instant now = Instant.now();
        List<HotspotSearchItem> deduplicated = deduplicate(allItems);
        deduplicated.sort(Comparator
                .comparingDouble((HotspotSearchItem item) -> rankingScore(item, now))
                .reversed()
                .thenComparing(item -> item.getPublishedAt() == null ? Instant.EPOCH : item.getPublishedAt(), Comparator.reverseOrder())
                .thenComparing(HotspotSearchItem::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        List<HotspotSearchItem> limitedItems = deduplicated.stream()
                .limit(boundedLimit)
                .toList();
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        for (HotspotSearchItem item : limitedItems) {
            sourceCounts.merge(item.getSource(), 1, Integer::sum);
        }

        return HotspotReport.builder()
                .query(normalizedQuery)
                .generatedAt(System.currentTimeMillis())
                .total(limitedItems.size())
                .sources(sources.stream().map(HotspotSource::getCode).toList())
                .sourceCounts(sourceCounts)
                .warnings(List.copyOf(warnings))
                .expandedQueries(List.copyOf(queries))
                .analyzed(false)
                .items(limitedItems)
                .build();
    }

    private HotspotReport emptyReport(String query,
                                      List<HotspotSource> sources,
                                      String warning,
                                      List<String> expandedQueries) {
        return HotspotReport.builder()
                .query(query)
                .generatedAt(System.currentTimeMillis())
                .total(0)
                .sources(sources.stream().map(HotspotSource::getCode).toList())
                .sourceCounts(Map.of())
                .warnings(StrUtil.isBlank(warning) ? List.of() : List.of(warning))
                .expandedQueries(expandedQueries)
                .analyzed(false)
                .items(List.of())
                .build();
    }

    private List<HotspotSource> resolveSources(Collection<HotspotSource> requestedSources) {
        if (requestedSources != null && !requestedSources.isEmpty()) {
            return List.copyOf(requestedSources);
        }
        return HotspotSource.parseList(hotspotProperties.getDefaultSources());
    }

    private List<String> normalizeQueries(String query, Collection<String> expandedQueries) {
        Set<String> result = new LinkedHashSet<>();
        result.add(StrUtil.blankToDefault(query, "AI").trim());
        if (expandedQueries != null) {
            for (String item : expandedQueries) {
                String normalized = StrUtil.trimToNull(item);
                if (normalized != null) {
                    result.add(normalized);
                }
                if (result.size() >= 6) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private SourceFetchResult fetchBySource(List<String> queries, HotspotSource source, int limit) {
        List<HotspotSearchItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String query : queries) {
            try {
                items.addAll(fetchSingleQueryBySource(query, source, limit));
                if (items.size() >= limit * 2) {
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to fetch hotspots from source={}, query={}", source.getCode(), query, e);
                warnings.add(source.getLabel() + "(" + query + ") 抓取失败：" + simplifyError(e));
            }
        }
        List<HotspotSearchItem> deduplicated = deduplicate(items);
        return new SourceFetchResult(
                source,
                deduplicated.stream().limit(limit * 2L).toList(),
                warnings.isEmpty() ? null : String.join("；", warnings)
        );
    }

    private List<HotspotSearchItem> fetchSingleQueryBySource(String query, HotspotSource source, int limit) throws IOException {
        return switch (source) {
            case TWITTER -> fetchTwitter(query, limit);
            case BING -> fetchBing(query, limit);
            case HACKERNEWS -> fetchHackerNews(query, limit);
            case SOGOU -> fetchSogou(query, limit);
            case BILIBILI -> fetchBilibili(query, limit);
            case WEIBO -> fetchWeibo(query, limit);
            case BAIDU -> fetchBaidu(query, limit);
            case DUCKDUCKGO -> fetchDuckDuckGo(query, limit);
            case REDDIT -> fetchReddit(query, limit);
        };
    }

    private List<HotspotSearchItem> fetchTwitter(String query, int limit) throws IOException {
        if (StrUtil.isBlank(hotspotProperties.getTwitterApiKey())) {
            throw new IOException("未配置 TWITTER_API_KEY");
        }
        waitForPermit(HotspotSource.TWITTER);
        HttpUrl url = HttpUrl.parse("https://api.twitterapi.io/twitter/tweet/advanced_search")
                .newBuilder()
                .addQueryParameter("query", buildTwitterQuery(query))
                .addQueryParameter("queryType", "Top")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", randomUserAgent())
                .header("X-API-Key", hotspotProperties.getTwitterApiKey())
                .header("Content-Type", "application/json")
                .build();
        JsonNode result = objectMapper.readTree(executeForString(request));
        JsonNode tweets = result.path("tweets");
        List<HotspotSearchItem> items = new ArrayList<>();
        if (!tweets.isArray()) {
            return items;
        }
        for (JsonNode tweet : tweets) {
            String urlValue = tweet.path("url").asText("");
            String text = cleanMarkup(tweet.path("text").asText(""));
            if (!isHttpUrl(urlValue) || StrUtil.isBlank(text) || isTwitterReply(text, tweet.path("type").asText(""))) {
                continue;
            }
            JsonNode author = tweet.path("author");
            long likes = tweet.path("likeCount").asLong(0);
            long retweets = tweet.path("retweetCount").asLong(0);
            long views = tweet.path("viewCount").asLong(0);
            long replies = tweet.path("replyCount").asLong(0);
            items.add(HotspotSearchItem.builder()
                    .title(truncate(text, 120))
                    .summary(text)
                    .url(urlValue)
                    .source(HotspotSource.TWITTER.getCode())
                    .sourceLabel(HotspotSource.TWITTER.getLabel())
                    .publishedAt(parseInstant(tweet.path("createdAt").asText("")))
                    .hotScore(calcHotScore(likes, retweets, views, replies, tweet.path("quoteCount").asLong(0)))
                    .viewCount(views)
                    .likeCount(likes)
                    .commentCount(replies)
                    .authorName(StrUtil.emptyToDefault(author.path("name").asText(""), null))
                    .authorUsername(StrUtil.emptyToDefault(author.path("userName").asText(""), null))
                    .build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchBing(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.BING);
        HttpUrl url = HttpUrl.parse("https://www.bing.com/search")
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("count", String.valueOf(Math.max(limit, 10)))
                .addQueryParameter("mkt", "zh-CN")
                .build();
        Request request = baseRequest(url, "zh-CN,zh;q=0.9,en;q=0.6");
        Document document = Jsoup.parse(executeForString(request));
        List<HotspotSearchItem> items = new ArrayList<>();
        for (Element element : document.select("li.b_algo")) {
            Element linkElement = element.selectFirst("h2 a");
            if (linkElement == null) {
                continue;
            }
            String title = linkElement.text().trim();
            String targetUrl = StrUtil.trimToEmpty(linkElement.attr("href"));
            String summary = textOrEmpty(element.selectFirst(".b_caption p"));
            if (!isHttpUrl(targetUrl) || StrUtil.isBlank(title)) {
                continue;
            }
            items.add(baseItemBuilder(title, summary, targetUrl, HotspotSource.BING).build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchHackerNews(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.HACKERNEWS);
        HttpUrl url = HttpUrl.parse("https://hn.algolia.com/api/v1/search_by_date")
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("tags", "story")
                .addQueryParameter("hitsPerPage", String.valueOf(Math.max(limit, 10)))
                .build();
        Request request = baseRequest(url, "en-US,en;q=0.8")
                .newBuilder()
                .header("Accept", "application/json")
                .build();
        JsonNode result = objectMapper.readTree(executeForString(request));
        JsonNode hits = result.path("hits");
        List<HotspotSearchItem> items = new ArrayList<>();
        if (!hits.isArray()) {
            return items;
        }
        for (JsonNode hit : hits) {
            String title = chooseHackerNewsTitle(hit);
            String targetUrl = chooseHackerNewsUrl(hit);
            Instant publishedAt = parseInstant(hit.path("created_at").asText(""));
            if (StrUtil.isBlank(title) || !isHttpUrl(targetUrl)) {
                continue;
            }
            long score = hit.path("points").asLong(0);
            long commentCount = hit.path("num_comments").asLong(0);
            items.add(baseItemBuilder(title,
                    StrUtil.blankToDefault(hit.path("story_text").asText(""), title),
                    targetUrl,
                    HotspotSource.HACKERNEWS)
                    .publishedAt(publishedAt)
                    .hotScore(calcHotScore(0L, score, 0L, commentCount, 0L))
                    .commentCount(commentCount)
                    .score(score)
                    .authorName(StrUtil.emptyToDefault(hit.path("author").asText(""), null))
                    .authorUsername(StrUtil.emptyToDefault(hit.path("author").asText(""), null))
                    .build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchSogou(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.SOGOU);
        HttpUrl url = HttpUrl.parse("https://www.sogou.com/web")
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("ie", "utf8")
                .build();
        Request request = baseRequest(url, "zh-CN,zh;q=0.9,en;q=0.6");
        Document document = Jsoup.parse(executeForString(request));
        List<HotspotSearchItem> items = new ArrayList<>();
        for (Element element : document.select(".vrwrap, .rb")) {
            Element linkElement = element.selectFirst("h3 a, .vr-title a, .vrTitle a");
            if (linkElement == null) {
                continue;
            }
            String title = linkElement.text().trim();
            String targetUrl = StrUtil.trimToEmpty(linkElement.attr("href"));
            if (targetUrl.startsWith("/link?url=")) {
                targetUrl = "https://www.sogou.com" + targetUrl;
            }
            String summary = StrUtil.blankToDefault(
                    textOrEmpty(element.selectFirst(".space-txt, .str-text-info, .str_info, .text-layout")),
                    textOrEmpty(element.selectFirst("p"))
            );
            if (StrUtil.isBlank(title) || !isHttpUrl(targetUrl) || title.contains("大家还在搜")) {
                continue;
            }
            items.add(baseItemBuilder(title, summary, targetUrl, HotspotSource.SOGOU).build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchBilibili(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.BILIBILI);
        HttpUrl url = HttpUrl.parse("https://api.bilibili.com/x/web-interface/search/type")
                .newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("search_type", "video")
                .addQueryParameter("order", "pubdate")
                .addQueryParameter("page", "1")
                .addQueryParameter("pagesize", String.valueOf(Math.max(limit, 10)))
                .build();
        Request request = baseRequest(url, "zh-CN,zh;q=0.9,en;q=0.6")
                .newBuilder()
                .header("Referer", "https://search.bilibili.com/")
                .header("Accept", "application/json")
                .header("Cookie", "buvid3=" + UUID.randomUUID() + "infoc")
                .build();
        JsonNode result = objectMapper.readTree(executeForString(request));
        if (result.path("code").asInt(-1) != 0) {
            throw new IOException("Bilibili API 返回错误码：" + result.path("code").asInt());
        }
        JsonNode videos = result.path("data").path("result");
        List<HotspotSearchItem> items = new ArrayList<>();
        if (!videos.isArray()) {
            return items;
        }
        for (JsonNode video : videos) {
            String bvid = video.path("bvid").asText("");
            String title = cleanMarkup(video.path("title").asText(""));
            if (StrUtil.isBlank(bvid) || StrUtil.isBlank(title)) {
                continue;
            }
            long viewCount = parseLong(video.path("play"));
            long likeCount = parseLong(video.path("like"));
            long commentCount = parseLong(video.path("review"));
            long danmakuCount = parseLong(video.path("danmaku"));
            Instant publishedAt = parseEpochSecond(video.path("pubdate").asLong(0));
            items.add(baseItemBuilder(title,
                    cleanMarkup(StrUtil.blankToDefault(video.path("description").asText(""), title)),
                    "https://www.bilibili.com/video/" + bvid,
                    HotspotSource.BILIBILI)
                    .publishedAt(publishedAt)
                    .hotScore(calcHotScore(likeCount, 0L, viewCount, commentCount, danmakuCount))
                    .viewCount(viewCount)
                    .likeCount(likeCount)
                    .commentCount(commentCount)
                    .danmakuCount(danmakuCount)
                    .authorName(StrUtil.emptyToDefault(video.path("author").asText(""), null))
                    .authorUsername(StrUtil.emptyToDefault(video.path("mid").asText(""), null))
                    .build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchWeibo(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.WEIBO);
        HttpUrl url = HttpUrl.parse("https://s.weibo.com/weibo")
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("Refer", "weibo_weibo")
                .build();
        Request request = baseRequest(url, "zh-CN,zh;q=0.9,en;q=0.6")
                .newBuilder()
                .header("Referer", "https://weibo.com/")
                .build();
        Document document = Jsoup.parse(executeForString(request));
        List<HotspotSearchItem> items = new ArrayList<>();
        for (Element card : document.select(".card-wrap")) {
            Element textElement = card.selectFirst(".txt");
            Element linkElement = card.selectFirst(".from a[href]");
            if (textElement == null || linkElement == null) {
                continue;
            }
            String text = cleanMarkup(textElement.text());
            String link = linkElement.absUrl("href");
            if (!isHttpUrl(link) || StrUtil.isBlank(text)) {
                continue;
            }
            items.add(baseItemBuilder(truncate(text, 120), text, link, HotspotSource.WEIBO)
                    .publishedAt(parseWeiboTime(linkElement.text()))
                    .build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchBaidu(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.BAIDU);
        HttpUrl url = HttpUrl.parse("https://www.baidu.com/s")
                .newBuilder()
                .addQueryParameter("wd", query)
                .addQueryParameter("rn", String.valueOf(Math.max(limit, 10)))
                .build();
        Request request = baseRequest(url, "zh-CN,zh;q=0.9,en;q=0.6");
        Document document = Jsoup.parse(executeForString(request));
        List<HotspotSearchItem> items = new ArrayList<>();
        for (Element element : document.select("div.result, div.c-container")) {
            Element linkElement = element.selectFirst("h3 a");
            if (linkElement == null) {
                continue;
            }
            String title = cleanMarkup(linkElement.text());
            String targetUrl = linkElement.absUrl("href");
            String summary = textOrEmpty(element.selectFirst(".c-abstract, .content-right_8Zs40, .c-span-last p"));
            if (StrUtil.isBlank(title) || !isHttpUrl(targetUrl)) {
                continue;
            }
            items.add(baseItemBuilder(title, summary, targetUrl, HotspotSource.BAIDU).build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchDuckDuckGo(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.DUCKDUCKGO);
        HttpUrl url = HttpUrl.parse("https://duckduckgo.com/html/")
                .newBuilder()
                .addQueryParameter("q", query)
                .build();
        Request request = baseRequest(url, "en-US,en;q=0.8");
        Document document = Jsoup.parse(executeForString(request));
        List<HotspotSearchItem> items = new ArrayList<>();
        for (Element element : document.select(".result")) {
            Element linkElement = element.selectFirst(".result__title a.result__a");
            if (linkElement == null) {
                continue;
            }
            String title = cleanMarkup(linkElement.text());
            String targetUrl = StrUtil.trimToEmpty(linkElement.attr("href"));
            String summary = textOrEmpty(element.selectFirst(".result__snippet"));
            if (StrUtil.isBlank(title) || !isHttpUrl(targetUrl)) {
                continue;
            }
            items.add(baseItemBuilder(title, summary, targetUrl, HotspotSource.DUCKDUCKGO).build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private List<HotspotSearchItem> fetchReddit(String query, int limit) throws IOException {
        waitForPermit(HotspotSource.REDDIT);
        HttpUrl url = HttpUrl.parse("https://www.reddit.com/search.json")
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("sort", "new")
                .addQueryParameter("limit", String.valueOf(Math.max(limit, 10)))
                .build();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", randomUserAgent())
                .header("Accept", "application/json")
                .build();
        JsonNode result = objectMapper.readTree(executeForString(request));
        JsonNode children = result.path("data").path("children");
        List<HotspotSearchItem> items = new ArrayList<>();
        if (!children.isArray()) {
            return items;
        }
        for (JsonNode child : children) {
            JsonNode data = child.path("data");
            String permalink = data.path("permalink").asText("");
            String title = cleanMarkup(data.path("title").asText(""));
            if (StrUtil.isBlank(title) || StrUtil.isBlank(permalink)) {
                continue;
            }
            String fullUrl = permalink.startsWith("http") ? permalink : "https://www.reddit.com" + permalink;
            long score = data.path("score").asLong(0);
            long comments = data.path("num_comments").asLong(0);
            items.add(baseItemBuilder(title,
                    cleanMarkup(StrUtil.blankToDefault(data.path("selftext").asText(""), title)),
                    fullUrl,
                    HotspotSource.REDDIT)
                    .publishedAt(parseEpochSecond(data.path("created_utc").asLong(0)))
                    .hotScore(calcHotScore(0L, score, 0L, comments, 0L))
                    .commentCount(comments)
                    .score(score)
                    .authorName(StrUtil.emptyToDefault(data.path("author").asText(""), null))
                    .authorUsername(StrUtil.emptyToDefault(data.path("author").asText(""), null))
                    .build());
            if (items.size() >= limit) {
                break;
            }
        }
        return items;
    }

    private HotspotSearchItem.HotspotSearchItemBuilder baseItemBuilder(String title,
                                                                       String summary,
                                                                       String url,
                                                                       HotspotSource source) {
        return HotspotSearchItem.builder()
                .title(title)
                .summary(StrUtil.blankToDefault(summary, title))
                .url(url)
                .source(source.getCode())
                .sourceLabel(source.getLabel())
                .hotScore(0D);
    }

    private Request baseRequest(HttpUrl url, String acceptLanguage) {
        return new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", randomUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", acceptLanguage)
                .build();
    }

    private String executeForString(Request request) throws IOException {
        OkHttpClient client = okHttpClient.newBuilder()
                .callTimeout(Duration.ofSeconds(Math.max(5, hotspotProperties.getTimeoutSeconds())))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private void waitForPermit(HotspotSource source) {
        long minInterval = MIN_INTERVAL_MS.getOrDefault(source, 1_000L);
        AtomicLong state = rateLimitState.computeIfAbsent(source, key -> new AtomicLong(0));
        while (true) {
            long last = state.get();
            long now = System.currentTimeMillis();
            long elapsed = now - last;
            if (elapsed >= minInterval) {
                if (state.compareAndSet(last, now)) {
                    return;
                }
                continue;
            }
            try {
                Thread.sleep(Math.min(minInterval - elapsed, 200L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<HotspotSearchItem> deduplicate(List<HotspotSearchItem> items) {
        Map<String, HotspotSearchItem> deduplicated = new LinkedHashMap<>();
        for (HotspotSearchItem item : items) {
            String normalizedUrl = normalizeUrl(item.getUrl());
            String key = StrUtil.isNotBlank(normalizedUrl)
                    ? normalizedUrl
                    : normalizeTitle(item.getTitle());
            if (StrUtil.isBlank(key)) {
                continue;
            }
            deduplicated.putIfAbsent(key, item);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private String normalizeUrl(String rawUrl) {
        if (StrUtil.isBlank(rawUrl)) {
            return "";
        }
        String normalized = rawUrl.trim();
        normalized = normalized.replaceFirst("(?i)^http://", "https://");
        normalized = normalized.replaceFirst("(?i)^https://www\\.", "https://");
        normalized = normalized.replaceAll("/+$", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeTitle(String title) {
        return StrUtil.blankToDefault(title, "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private double rankingScore(HotspotSearchItem item, Instant now) {
        double hotScore = item.getHotScore() == null ? 0D : item.getHotScore();
        double freshness = 0D;
        if (item.getPublishedAt() != null) {
            long ageHours = Math.max(0L, Duration.between(item.getPublishedAt(), now).toHours());
            freshness = Math.max(0D, 72D - Math.min(ageHours, 72L));
        }
        return hotScore + freshness + sourceWeight(item.getSource());
    }

    private double sourceWeight(String source) {
        return switch (StrUtil.blankToDefault(source, "")) {
            case "twitter" -> 8D;
            case "bilibili" -> 6D;
            case "hackernews" -> 5D;
            case "reddit" -> 5D;
            case "weibo" -> 4D;
            case "bing", "baidu", "duckduckgo" -> 3D;
            case "sogou" -> 2D;
            default -> 0D;
        };
    }

    private double calcHotScore(long likes, long sharesOrScore, long views, long comments, long danmaku) {
        return likes * 10D
                + sharesOrScore * 5D
                + comments * 4D
                + danmaku * 1.5D
                + Math.log10(Math.max(views, 1L)) * 2D;
    }

    private String chooseHackerNewsTitle(JsonNode hit) {
        String title = hit.path("title").asText("");
        if (StrUtil.isNotBlank(title)) {
            return title;
        }
        return hit.path("story_title").asText("");
    }

    private String chooseHackerNewsUrl(JsonNode hit) {
        String url = hit.path("url").asText("");
        if (isHttpUrl(url)) {
            return url;
        }
        String storyUrl = hit.path("story_url").asText("");
        if (isHttpUrl(storyUrl)) {
            return storyUrl;
        }
        String objectId = hit.path("objectID").asText("");
        if (StrUtil.isBlank(objectId)) {
            return "";
        }
        return "https://news.ycombinator.com/item?id=" + objectId;
    }

    private String buildTwitterQuery(String keyword) {
        LocalDate sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(7);
        return keyword
                + " -filter:retweets -filter:replies since:"
                + sinceDate.format(TWITTER_SINCE_FORMAT)
                + " min_faves:10";
    }

    private boolean isTwitterReply(String text, String type) {
        return StrUtil.containsIgnoreCase(type, "reply") || text.startsWith("@");
    }

    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    private boolean isHttpUrl(String value) {
        return StrUtil.isNotBlank(value) && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String textOrEmpty(Element element) {
        return element == null ? "" : element.text().trim();
    }

    private String cleanMarkup(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        return raw.replaceAll("</?em[^>]*>", "").replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int maxLength) {
        if (StrUtil.isBlank(text) || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private long parseLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        if (node.isNumber()) {
            return node.asLong(0L);
        }
        String raw = cleanMarkup(node.asText(""));
        raw = raw.replaceAll("[^0-9]", "");
        if (StrUtil.isBlank(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Instant parseEpochSecond(long epochSecond) {
        if (epochSecond <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(epochSecond);
    }

    private Instant parseInstant(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Instant parseWeiboTime(String raw) {
        String text = StrUtil.trimToNull(raw);
        if (text == null) {
            return null;
        }
        text = text.replace("\u00a0", " ").trim();
        try {
            if (text.endsWith("分钟前")) {
                long minutes = Long.parseLong(text.replace("分钟前", "").trim());
                return Instant.now().minus(Duration.ofMinutes(minutes));
            }
            if (text.endsWith("小时前")) {
                long hours = Long.parseLong(text.replace("小时前", "").trim());
                return Instant.now().minus(Duration.ofHours(hours));
            }
            if (text.contains("-")) {
                return LocalDate.parse(text, DateTimeFormatter.ofPattern("MM-dd"))
                        .atStartOfDay()
                        .toInstant(ZoneOffset.ofHours(8));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String simplifyError(Exception e) {
        String message = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
        return message.length() > 80 ? message.substring(0, 80) + "..." : message;
    }

    private record SourceFetchResult(HotspotSource source, List<HotspotSearchItem> items, String warning) {
    }
}
