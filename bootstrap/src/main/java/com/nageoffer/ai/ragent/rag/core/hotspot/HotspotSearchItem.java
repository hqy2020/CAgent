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

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * 多源热点条目。
 */
@Getter
@Builder
public class HotspotSearchItem {

    private final String title;

    private final String summary;

    private final String url;

    private final String source;

    private final String sourceLabel;

    private final Instant publishedAt;

    private final Double hotScore;

    private final Long viewCount;

    private final Long likeCount;

    private final Long commentCount;

    private final Long score;

    private final Long danmakuCount;

    private final String authorName;

    private final String authorUsername;

    private final Double relevanceScore;

    private final Double credibilityScore;

    private final String verdict;

    private final String analysisSummary;

    private final String analysisReason;

    private final List<String> matchedKeywords;
}
