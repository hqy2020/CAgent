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

package com.openingcloud.ai.ragent.rag.core.web;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 联网搜索相关的规则集。
 *
 * <p>用于统一判断：
 * <ul>
 *   <li>是否属于新闻/热点联网搜索</li>
 *   <li>是否属于外部实体介绍类通用联网搜索</li>
 *   <li>如何从自然语言中抽取可执行的搜索 query</li>
 * </ul>
 */
public final class WebSearchIntentRules {

    public static final String WEB_SEARCH_TOOL_ID = "web_search";
    public static final String WEB_NEWS_SEARCH_TOOL_ID = "web_news_search";
    public static final String WEB_REALTIME_SEARCH_TOOL_ID = "web_realtime_search";
    public static final String WEB_SEARCH_GENERAL_NODE_ID = "web-search-general";
    public static final String WEB_SEARCH_REALTIME_NODE_ID = "web-search-realtime";

    private static final Pattern NEWS_TOPIC_HINT = Pattern.compile(
            "(新闻|热点|热搜|快讯|头条|动态|简报|资讯)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEWS_TIME_HINT = Pattern.compile(
            "(实时|最新|最近|今日|今天)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPLICIT_WEB_SEARCH_HINT = Pattern.compile(
            "(联网|上网|互联网|web|internet|google|bing|百度|搜狗|duckduckgo|搜索|搜一下|搜一搜|查一下|查一查|查找)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXTERNAL_ENTITY_INTRO_HINT = Pattern.compile(
            "(做什么的|是做什么的|是干什么的|是干嘛的|属于谁|属于哪个公司|属于哪个业务|哪个业务|什么业务|什么团队|是什么团队|负责什么|功能|作用|职责|职能|介绍一下|介绍下|了解一下|是什么)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REALTIME_FACT_HINT = Pattern.compile(
            "(天气|气温|温度|降雨|下雨|降雪|台风|空气质量|空气污染|aqi|pm2\\.5|湿度|风力|股价|市值|汇率|币价|油价|金价|票房|比分|赛程|战绩|航班|路况|限行|拥堵|行情|价格|涨跌)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REALTIME_TIME_HINT = Pattern.compile(
            "(今天|今日|现在|当前|实时|最新|最近|今晚|明天|本周)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CORPORATE_ENTITY_HINT = Pattern.compile(
            "(公司|团队|平台|业务|部门|事业部|集团|旗下|招聘|岗位|阿里|阿里巴巴|1688|淘宝|天猫|支付宝|蚂蚁|菜鸟|飞书|钉钉|高德|夸克|阿里云|淘天|通义|字节|腾讯|京东|美团|拼多多|小红书|快手|百度|华为|小米|网易|滴滴|携程|OpenAI|Anthropic|DeepSeek|Claude|Gemini)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONSUMER_APP_ENTITY_HINT = Pattern.compile(
            "(抖音|微信|企业微信|qq|微博|知乎|豆包|剪映|哔哩哔哩|bilibili|b站|小红书|快手|支付宝|淘宝|天猫|京东|美团|拼多多|滴滴|携程|去哪儿|高德地图|百度地图|网易云音乐|qq音乐|酷狗|酷我|优酷|爱奇艺|腾讯视频|得物|闲鱼|豆瓣|微信读书|小宇宙|喜马拉雅)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MIXED_ENTITY_TOKEN_HINT = Pattern.compile(
            "(?=.*\\d)[\\p{L}\\p{N}]+(?:[-_/][\\p{L}\\p{N}]+)+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[？?。！!；;，,]+$");

    private static final List<String> LEADING_FILLERS = List.of(
            "请帮我",
            "帮我",
            "麻烦你",
            "麻烦",
            "请你",
            "给我",
            "我想",
            "请问",
            "能不能",
            "可以",
            "联网搜索",
            "上网搜索",
            "联网查一下",
            "联网查一查",
            "搜索一下",
            "搜一下",
            "搜一搜",
            "查一下",
            "查一查",
            "查找",
            "搜索",
            "联网",
            "上网"
    );

    private static final List<String> NEWS_NOISE = List.of(
            "给出处链接",
            "并给出处链接",
            "并附上出处链接",
            "并附链接",
            "的最新动态",
            "最新动态",
            "的热点",
            "热点",
            "的新闻",
            "新闻",
            "简报",
            "快讯",
            "今天",
            "今日",
            "最近",
            "最新"
    );
    private static final List<String> REALTIME_NOISE = List.of(
            "给我",
            "帮我",
            "请问",
            "请告诉我",
            "请帮我",
            "查一下",
            "查一查",
            "搜一下",
            "搜一搜",
            "看一下",
            "看一眼",
            "查询",
            "怎么样",
            "如何",
            "多少",
            "是多少",
            "情况",
            "最新",
            "实时",
            "现在",
            "当前",
            "今天",
            "今日"
    );

    private WebSearchIntentRules() {
    }

    public static boolean isNewsSearchQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return false;
        }
        if (REALTIME_FACT_HINT.matcher(normalized).find()) {
            return false;
        }
        return NEWS_TOPIC_HINT.matcher(normalized).find()
                || (EXPLICIT_WEB_SEARCH_HINT.matcher(normalized).find()
                && NEWS_TIME_HINT.matcher(normalized).find());
    }

    public static boolean isExternalEntityIntroQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return false;
        }
        if (!EXTERNAL_ENTITY_INTRO_HINT.matcher(normalized).find()) {
            return false;
        }
        return CORPORATE_ENTITY_HINT.matcher(normalized).find()
                || CONSUMER_APP_ENTITY_HINT.matcher(normalized).find()
                || MIXED_ENTITY_TOKEN_HINT.matcher(normalized).find();
    }

    public static boolean isGeneralWebSearchQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isEmpty() || isNewsSearchQuestion(normalized) || isRealtimeSearchQuestion(normalized)) {
            return false;
        }
        return isExternalEntityIntroQuestion(normalized) || EXPLICIT_WEB_SEARCH_HINT.matcher(normalized).find();
    }

    public static boolean isRealtimeSearchQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isEmpty() || isNewsSearchQuestion(normalized)) {
            return false;
        }
        if (REALTIME_FACT_HINT.matcher(normalized).find()) {
            return true;
        }
        return EXPLICIT_WEB_SEARCH_HINT.matcher(normalized).find()
                && REALTIME_TIME_HINT.matcher(normalized).find();
    }

    public static String extractQuery(String question, String toolId) {
        String original = StrUtil.blankToDefault(question, "")
                .replace('　', ' ')
                .trim();
        if (original.isEmpty()) {
            return "";
        }

        String extracted = stripLeadingFillers(original);
        if (WEB_NEWS_SEARCH_TOOL_ID.equals(toolId)) {
            extracted = stripNewsNoise(extracted);
            extracted = extracted.replaceAll("的\\s*\\d+\\s*条.*$", "");
            extracted = extracted.replaceAll("\\d+\\s*条.*$", "");
        }
        if (WEB_REALTIME_SEARCH_TOOL_ID.equals(toolId)) {
            extracted = stripRealtimeNoise(extracted);
        }
        extracted = TRAILING_PUNCTUATION.matcher(StrUtil.trim(extracted)).replaceAll("");
        return StrUtil.trim(extracted);
    }

    private static String stripLeadingFillers(String question) {
        String normalized = StrUtil.blankToDefault(question, "").trim();
        boolean changed;
        do {
            changed = false;
            for (String filler : LEADING_FILLERS) {
                if (StrUtil.startWithIgnoreCase(normalized, filler)) {
                    normalized = normalized.substring(filler.length()).trim();
                    changed = true;
                }
            }
        } while (changed);
        return normalized;
    }

    private static String stripNewsNoise(String question) {
        String normalized = StrUtil.blankToDefault(question, "").trim();
        for (String token : NEWS_NOISE) {
            normalized = normalized.replace(token, " ");
        }
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private static String stripRealtimeNoise(String question) {
        String normalized = StrUtil.blankToDefault(question, "").trim();
        for (String token : REALTIME_NOISE) {
            normalized = normalized.replace(token, " ");
        }
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.endsWith("吗")) {
            normalized = StrUtil.removeSuffix(normalized, "吗").trim();
        }
        return normalized;
    }

    private static String normalize(String question) {
        return StrUtil.blankToDefault(question, "")
                .replace('　', ' ')
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
