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

package com.openingcloud.ai.ragent.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardFeedbackReasonVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardFeedbackSummaryVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardNegativeSampleVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardOverviewGroupVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardOverviewKpiVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardTrendPointVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardTrendSeriesVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationDO;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.openingcloud.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.openingcloud.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.openingcloud.ai.ragent.user.dao.entity.UserDO;
import com.openingcloud.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final String CHAT_TRACE_NAME = "rag-stream-chat";
    private static final String TRACE_STATUS_SUCCESS = "SUCCESS";
    private static final String TRACE_STATUS_ERROR = "ERROR";
    private static final long SLOW_RESPONSE_THRESHOLD_MS = 20_000L;
    private static final int TOP_REASON_LIMIT = 5;
    private static final int NEGATIVE_SAMPLE_LIMIT = 5;
    private static final int CONTENT_SNIPPET_LIMIT = 140;
    private static final List<String> NO_DOC_MARKERS = List.of(
            "未检索到与问题相关的文档内容",
            "检索阶段未命中可用上下文"
    );

    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final RagTraceRunMapper ragTraceRunMapper;
    private final MessageFeedbackMapper messageFeedbackMapper;

    public DashboardOverviewVO getOverview(String window) {
        WindowRange range = resolveWindow(window);

        long totalUsers = safeCount(userMapper.selectCount(Wrappers.<UserDO>query()));
        long totalUsersBefore = safeCount(userMapper.selectCount(Wrappers.<UserDO>query()
                .lt("create_time", toDate(range.currentStart()))));

        long totalSessions = safeCount(conversationMapper.selectCount(Wrappers.<ConversationDO>query()));
        long totalSessionsBefore = safeCount(conversationMapper.selectCount(Wrappers.<ConversationDO>query()
                .lt("create_time", toDate(range.currentStart()))));

        long totalMessages = safeCount(conversationMessageMapper.selectCount(Wrappers.<ConversationMessageDO>query()));
        long totalMessagesBefore = safeCount(conversationMessageMapper.selectCount(Wrappers.<ConversationMessageDO>query()
                .lt("create_time", toDate(range.currentStart()))));

        List<ConversationMessageDO> currentMessages = listMessages(range.currentStart(), range.currentEnd(), false);
        List<ConversationMessageDO> previousMessages = listMessages(range.previousStart(), range.previousEnd(), false);

        long activeUsers = countDistinct(currentMessages, ConversationMessageDO::getUserId);
        long previousActiveUsers = countDistinct(previousMessages, ConversationMessageDO::getUserId);
        long activeSessions = countDistinct(currentMessages, ConversationMessageDO::getConversationId);
        long previousActiveSessions = countDistinct(previousMessages, ConversationMessageDO::getConversationId);
        long currentMessageCount = currentMessages.size();
        long previousMessageCount = previousMessages.size();
        List<MessageFeedbackDO> currentFeedback = listFeedback(range.currentStart(), range.currentEnd());
        List<MessageFeedbackDO> recentFeedback = listFeedback(
                range.currentEnd().minus(Duration.ofHours(24)),
                range.currentEnd()
        );

        return DashboardOverviewVO.builder()
                .window(range.window())
                .compareWindow(range.compareWindow())
                .updatedAt(System.currentTimeMillis())
                .kpis(DashboardOverviewGroupVO.builder()
                        .totalUsers(buildKpi(totalUsers, totalUsersBefore))
                        .activeUsers(buildKpi(activeUsers, previousActiveUsers))
                        .totalSessions(buildKpi(totalSessions, totalSessionsBefore))
                        .sessions24h(buildKpi(activeSessions, previousActiveSessions))
                        .totalMessages(buildKpi(totalMessages, totalMessagesBefore))
                        .messages24h(buildKpi(currentMessageCount, previousMessageCount))
                        .build())
                .feedbackSummary(buildFeedbackSummary(currentFeedback, recentFeedback))
                .negativeSamples(buildNegativeSamples(currentFeedback))
                .build();
    }

    public DashboardPerformanceVO getPerformance(String window) {
        WindowRange range = resolveWindow(window);
        List<RagTraceRunDO> chatRuns = listChatTraceRuns(range.currentStart(), range.currentEnd());
        List<RagTraceRunDO> completedRuns = chatRuns.stream()
                .filter(this::isCompletedTrace)
                .toList();
        List<ConversationMessageDO> messages = listMessages(range.currentStart(), range.currentEnd(), true);

        long totalRuns = completedRuns.size();
        long successRuns = completedRuns.stream()
                .filter(run -> TRACE_STATUS_SUCCESS.equalsIgnoreCase(run.getStatus()))
                .count();
        long errorRuns = completedRuns.stream()
                .filter(run -> TRACE_STATUS_ERROR.equalsIgnoreCase(run.getStatus()))
                .count();
        long slowRuns = completedRuns.stream()
                .filter(run -> safeLong(run.getDurationMs()) > SLOW_RESPONSE_THRESHOLD_MS)
                .count();

        List<Long> durations = completedRuns.stream()
                .map(RagTraceRunDO::getDurationMs)
                .filter(value -> value != null && value >= 0)
                .sorted()
                .toList();

        List<ConversationMessageDO> assistantMessages = messages.stream()
                .filter(message -> "assistant".equalsIgnoreCase(message.getRole()))
                .toList();
        long noDocCount = assistantMessages.stream()
                .filter(message -> containsNoDocMarker(message.getContent()))
                .count();

        return DashboardPerformanceVO.builder()
                .window(range.window())
                .avgLatencyMs(average(durations))
                .p95LatencyMs(percentile95(durations))
                .successRate(percent(successRuns, totalRuns))
                .errorRate(percent(errorRuns, totalRuns))
                .noDocRate(percent(noDocCount, assistantMessages.size()))
                .slowRate(percent(slowRuns, totalRuns))
                .build();
    }

    public DashboardTrendsVO getTrends(String metric, String window, String granularity) {
        TrendRange range = resolveTrendRange(window, granularity);
        return switch (normalize(metric)) {
            case "sessions" -> buildSessionTrends(range);
            case "messages" -> buildMessageCountTrends(range);
            case "activeusers" -> buildActiveUserTrends(range);
            case "avglatency" -> buildLatencyTrends(range);
            case "quality" -> buildQualityTrends(range);
            default -> throw new IllegalArgumentException("Unsupported dashboard metric: " + metric);
        };
    }

    private DashboardTrendsVO buildSessionTrends(TrendRange range) {
        List<ConversationMessageDO> messages = listMessages(range.start(), range.end(), false);
        return buildDistinctMessageTrend("sessions", range, messages, ConversationMessageDO::getConversationId, "会话数");
    }

    private DashboardTrendsVO buildMessageCountTrends(TrendRange range) {
        List<ConversationMessageDO> messages = listMessages(range.start(), range.end(), false);
        List<DashboardTrendPointVO> points = messages.isEmpty()
                ? List.of()
                : toPoints(countMessageBuckets(messages, range), range);
        return buildTrendResponse("messages", range, List.of(series("消息数", points)));
    }

    private DashboardTrendsVO buildActiveUserTrends(TrendRange range) {
        List<ConversationMessageDO> messages = listMessages(range.start(), range.end(), false);
        return buildDistinctMessageTrend("activeUsers", range, messages, ConversationMessageDO::getUserId, "活跃用户");
    }

    private DashboardTrendsVO buildLatencyTrends(TrendRange range) {
        List<RagTraceRunDO> chatRuns = listChatTraceRuns(range.start(), range.end());
        List<RagTraceRunDO> completedRuns = chatRuns.stream()
                .filter(this::isCompletedTrace)
                .toList();
        List<DashboardTrendPointVO> points = completedRuns.isEmpty()
                ? List.of()
                : toPoints(buildAverageDurationBuckets(completedRuns, range), range);
        return buildTrendResponse("avgLatency", range, List.of(series("平均响应时间", points)));
    }

    private DashboardTrendsVO buildQualityTrends(TrendRange range) {
        List<RagTraceRunDO> chatRuns = listChatTraceRuns(range.start(), range.end());
        List<RagTraceRunDO> completedRuns = chatRuns.stream()
                .filter(this::isCompletedTrace)
                .toList();
        List<ConversationMessageDO> assistantMessages = listMessages(range.start(), range.end(), true).stream()
                .filter(message -> "assistant".equalsIgnoreCase(message.getRole()))
                .toList();

        List<DashboardTrendPointVO> errorPoints = completedRuns.isEmpty()
                ? List.of()
                : toPoints(buildRateBuckets(
                completedRuns,
                range,
                run -> TRACE_STATUS_ERROR.equalsIgnoreCase(run.getStatus())
        ), range);
        List<DashboardTrendPointVO> slowPoints = completedRuns.isEmpty()
                ? List.of()
                : toPoints(buildRateBuckets(
                completedRuns,
                range,
                run -> safeLong(run.getDurationMs()) > SLOW_RESPONSE_THRESHOLD_MS
        ), range);
        List<DashboardTrendPointVO> noDocPoints = assistantMessages.isEmpty()
                ? List.of()
                : toPoints(buildRateBuckets(
                assistantMessages,
                range,
                message -> containsNoDocMarker(message.getContent())
        ), range);

        return buildTrendResponse("quality", range, List.of(
                series("错误率", errorPoints),
                series("无知识率", noDocPoints),
                series("慢响应率", slowPoints)
        ));
    }

    private DashboardTrendsVO buildDistinctMessageTrend(String metric,
                                                        TrendRange range,
                                                        List<ConversationMessageDO> messages,
                                                        Function<ConversationMessageDO, String> keyExtractor,
                                                        String seriesName) {
        List<DashboardTrendPointVO> points = messages.isEmpty()
                ? List.of()
                : toPoints(countDistinctMessageBuckets(messages, range, keyExtractor), range);
        return buildTrendResponse(metric, range, List.of(series(seriesName, points)));
    }

    private DashboardTrendsVO buildTrendResponse(String metric,
                                                 TrendRange range,
                                                 List<DashboardTrendSeriesVO> series) {
        return DashboardTrendsVO.builder()
                .metric(metric)
                .window(range.window())
                .granularity(range.granularity().wireValue())
                .series(series)
                .build();
    }

    private DashboardTrendSeriesVO series(String name, List<DashboardTrendPointVO> points) {
        return DashboardTrendSeriesVO.builder()
                .name(name)
                .data(points)
                .build();
    }

    private DashboardOverviewKpiVO buildKpi(long current, long previous) {
        return DashboardOverviewKpiVO.builder()
                .value(current)
                .delta(current - previous)
                .deltaPct(deltaPct(current, previous))
                .build();
    }

    private DashboardFeedbackSummaryVO buildFeedbackSummary(List<MessageFeedbackDO> currentFeedback,
                                                            List<MessageFeedbackDO> recentFeedback) {
        long totalFeedback = currentFeedback == null ? 0L : currentFeedback.size();
        long satisfiedCount = currentFeedback == null ? 0L : currentFeedback.stream()
                .filter(record -> Objects.equals(record.getVote(), 1))
                .count();
        long dissatisfiedCount = currentFeedback == null ? 0L : currentFeedback.stream()
                .filter(record -> Objects.equals(record.getVote(), -1))
                .count();
        long recentNegativeCount24h = recentFeedback == null ? 0L : recentFeedback.stream()
                .filter(record -> Objects.equals(record.getVote(), -1))
                .count();

        Map<String, Long> reasonCountMap = currentFeedback == null ? Map.of() : currentFeedback.stream()
                .filter(record -> Objects.equals(record.getVote(), -1))
                .map(MessageFeedbackDO::getReason)
                .filter(reason -> reason != null && !reason.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<DashboardFeedbackReasonVO> topReasons = reasonCountMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_REASON_LIMIT)
                .map(entry -> DashboardFeedbackReasonVO.builder()
                        .reason(entry.getKey())
                        .count(entry.getValue())
                        .ratio(percent(entry.getValue(), dissatisfiedCount))
                        .build())
                .toList();

        return DashboardFeedbackSummaryVO.builder()
                .totalCount(totalFeedback)
                .satisfiedCount(satisfiedCount)
                .dissatisfiedCount(dissatisfiedCount)
                .satisfactionRate(percent(satisfiedCount, totalFeedback))
                .dissatisfactionRate(percent(dissatisfiedCount, totalFeedback))
                .recentNegativeCount24h(recentNegativeCount24h)
                .topReasons(topReasons)
                .build();
    }

    private List<DashboardNegativeSampleVO> buildNegativeSamples(List<MessageFeedbackDO> feedbackRecords) {
        if (feedbackRecords == null || feedbackRecords.isEmpty()) {
            return List.of();
        }
        List<MessageFeedbackDO> negatives = feedbackRecords.stream()
                .filter(record -> Objects.equals(record.getVote(), -1))
                .sorted(Comparator.comparing(
                        MessageFeedbackDO::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(NEGATIVE_SAMPLE_LIMIT)
                .toList();
        if (negatives.isEmpty()) {
            return List.of();
        }

        Map<Long, ConversationMessageDO> assistantMessages = listMessagesByIds(negatives.stream()
                .map(MessageFeedbackDO::getMessageId)
                .filter(Objects::nonNull)
                .toList()).stream().collect(Collectors.toMap(
                ConversationMessageDO::getId,
                Function.identity(),
                (first, second) -> first
        ));

        List<DashboardNegativeSampleVO> samples = new ArrayList<>(negatives.size());
        for (MessageFeedbackDO feedback : negatives) {
            ConversationMessageDO assistantMessage = assistantMessages.get(feedback.getMessageId());
            String conversationId = assistantMessage != null
                    ? assistantMessage.getConversationId()
                    : feedback.getConversationId();
            Date answerTime = assistantMessage != null && assistantMessage.getCreateTime() != null
                    ? assistantMessage.getCreateTime()
                    : feedback.getCreateTime();
            ConversationMessageDO questionMessage = findPreviousUserMessage(conversationId, answerTime);

            samples.add(DashboardNegativeSampleVO.builder()
                    .messageId(feedback.getMessageId() == null ? null : String.valueOf(feedback.getMessageId()))
                    .conversationId(conversationId)
                    .question(summarizeContent(questionMessage == null ? null : questionMessage.getContent()))
                    .answer(summarizeContent(assistantMessage == null ? null : assistantMessage.getContent()))
                    .reason(defaultText(feedback.getReason(), "未填写"))
                    .comment(feedback.getComment())
                    .createdAt(toEpochMilli(feedback.getCreateTime()))
                    .build());
        }
        return samples;
    }

    private Map<Long, Long> countMessageBuckets(List<ConversationMessageDO> messages, TrendRange range) {
        LinkedHashMap<Long, Long> buckets = initLongBuckets(range);
        for (ConversationMessageDO message : messages) {
            Long bucketKey = toBucketKey(message.getCreateTime(), range);
            if (bucketKey == null) {
                continue;
            }
            buckets.computeIfPresent(bucketKey, (key, value) -> value + 1);
        }
        return buckets;
    }

    private Map<Long, Long> countDistinctMessageBuckets(List<ConversationMessageDO> messages,
                                                        TrendRange range,
                                                        Function<ConversationMessageDO, String> keyExtractor) {
        LinkedHashMap<Long, Set<String>> buckets = initDistinctBuckets(range);
        for (ConversationMessageDO message : messages) {
            Long bucketKey = toBucketKey(message.getCreateTime(), range);
            String key = keyExtractor.apply(message);
            if (bucketKey == null || key == null || key.isBlank()) {
                continue;
            }
            buckets.computeIfPresent(bucketKey, (ignored, values) -> {
                values.add(key);
                return values;
            });
        }
        LinkedHashMap<Long, Long> counts = new LinkedHashMap<>();
        buckets.forEach((bucket, values) -> counts.put(bucket, (long) values.size()));
        return counts;
    }

    private Map<Long, Double> buildAverageDurationBuckets(List<RagTraceRunDO> runs, TrendRange range) {
        LinkedHashMap<Long, AverageAccumulator> buckets = new LinkedHashMap<>();
        for (Long bucket : range.bucketKeys()) {
            buckets.put(bucket, new AverageAccumulator());
        }
        for (RagTraceRunDO run : runs) {
            Long bucketKey = toBucketKey(run.getStartTime(), range);
            long duration = safeLong(run.getDurationMs());
            if (bucketKey == null || duration < 0) {
                continue;
            }
            AverageAccumulator accumulator = buckets.get(bucketKey);
            if (accumulator == null) {
                continue;
            }
            accumulator.total += duration;
            accumulator.count += 1;
        }

        LinkedHashMap<Long, Double> averages = new LinkedHashMap<>();
        buckets.forEach((bucket, accumulator) -> averages.put(
                bucket,
                accumulator.count == 0 ? 0D : accumulator.total * 1.0D / accumulator.count
        ));
        return averages;
    }

    private <T> Map<Long, Double> buildRateBuckets(List<T> items,
                                                   TrendRange range,
                                                   Function<T, Boolean> matcher) {
        LinkedHashMap<Long, RateAccumulator> buckets = new LinkedHashMap<>();
        for (Long bucket : range.bucketKeys()) {
            buckets.put(bucket, new RateAccumulator());
        }
        for (T item : items) {
            Date timestamp = extractTimestamp(item);
            Long bucketKey = toBucketKey(timestamp, range);
            if (bucketKey == null) {
                continue;
            }
            RateAccumulator accumulator = buckets.get(bucketKey);
            if (accumulator == null) {
                continue;
            }
            accumulator.total += 1;
            if (Boolean.TRUE.equals(matcher.apply(item))) {
                accumulator.matched += 1;
            }
        }

        LinkedHashMap<Long, Double> rates = new LinkedHashMap<>();
        buckets.forEach((bucket, accumulator) -> rates.put(bucket, percent(accumulator.matched, accumulator.total)));
        return rates;
    }

    private Date extractTimestamp(Object item) {
        if (item instanceof ConversationMessageDO message) {
            return message.getCreateTime();
        }
        if (item instanceof RagTraceRunDO traceRun) {
            return traceRun.getStartTime();
        }
        return null;
    }

    private List<DashboardTrendPointVO> toPoints(Map<Long, ? extends Number> values, TrendRange range) {
        List<DashboardTrendPointVO> points = new ArrayList<>(range.bucketKeys().size());
        for (Long bucket : range.bucketKeys()) {
            Number value = values.get(bucket);
            points.add(DashboardTrendPointVO.builder()
                    .ts(bucket)
                    .value(value == null ? 0D : value.doubleValue())
                    .build());
        }
        return points;
    }

    private LinkedHashMap<Long, Long> initLongBuckets(TrendRange range) {
        LinkedHashMap<Long, Long> buckets = new LinkedHashMap<>();
        for (Long bucket : range.bucketKeys()) {
            buckets.put(bucket, 0L);
        }
        return buckets;
    }

    private LinkedHashMap<Long, Set<String>> initDistinctBuckets(TrendRange range) {
        LinkedHashMap<Long, Set<String>> buckets = new LinkedHashMap<>();
        for (Long bucket : range.bucketKeys()) {
            buckets.put(bucket, new java.util.HashSet<>());
        }
        return buckets;
    }

    private List<ConversationMessageDO> listMessages(Instant start, Instant end, boolean includeContent) {
        if (includeContent) {
            return conversationMessageMapper.selectList(Wrappers.<ConversationMessageDO>query()
                    .ge("create_time", toDate(start))
                    .lt("create_time", toDate(end))
                    .select(
                            "conversation_id",
                            "user_id",
                            "role",
                            "content",
                            "create_time"
                    ));
        }
        return conversationMessageMapper.selectList(Wrappers.<ConversationMessageDO>query()
                .ge("create_time", toDate(start))
                .lt("create_time", toDate(end))
                .select(
                        "conversation_id",
                        "user_id",
                        "create_time"
                ));
    }

    private List<MessageFeedbackDO> listFeedback(Instant start, Instant end) {
        return messageFeedbackMapper.selectList(Wrappers.<MessageFeedbackDO>query()
                .ge("create_time", toDate(start))
                .lt("create_time", toDate(end))
                .orderByDesc("create_time")
                .select(
                        "id",
                        "message_id",
                        "conversation_id",
                        "vote",
                        "reason",
                        "comment",
                        "create_time"
                ));
    }

    private List<ConversationMessageDO> listMessagesByIds(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return conversationMessageMapper.selectList(Wrappers.<ConversationMessageDO>query()
                .in("id", messageIds)
                .select(
                        "id",
                        "conversation_id",
                        "role",
                        "content",
                        "create_time"
                ));
    }

    private ConversationMessageDO findPreviousUserMessage(String conversationId, Date beforeTime) {
        if (conversationId == null || conversationId.isBlank() || beforeTime == null) {
            return null;
        }
        return conversationMessageMapper.selectOne(Wrappers.<ConversationMessageDO>query()
                .eq("conversation_id", conversationId)
                .eq("role", "user")
                .lt("create_time", beforeTime)
                .orderByDesc("create_time")
                .last("limit 1")
                .select(
                        "id",
                        "content",
                        "create_time"
                ));
    }

    private List<RagTraceRunDO> listChatTraceRuns(Instant start, Instant end) {
        return ragTraceRunMapper.selectList(Wrappers.<RagTraceRunDO>query()
                .eq("trace_name", CHAT_TRACE_NAME)
                .ge("start_time", toDate(start))
                .lt("start_time", toDate(end))
                .select(
                        "status",
                        "duration_ms",
                        "start_time",
                        "trace_name"
                ));
    }

    private boolean isCompletedTrace(RagTraceRunDO run) {
        if (run == null || run.getStatus() == null) {
            return false;
        }
        return TRACE_STATUS_SUCCESS.equalsIgnoreCase(run.getStatus())
                || TRACE_STATUS_ERROR.equalsIgnoreCase(run.getStatus());
    }

    private long countDistinct(List<ConversationMessageDO> messages,
                               Function<ConversationMessageDO, String> keyExtractor) {
        return messages.stream()
                .map(keyExtractor)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
    }

    private boolean containsNoDocMarker(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (String marker : NO_DOC_MARKERS) {
            if (content.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private Double average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        return values.stream().mapToLong(Long::longValue).average().orElse(0D);
    }

    private Double percentile95(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        int index = (int) Math.ceil(values.size() * 0.95D) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        return values.get(index).doubleValue();
    }

    private Double percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return numerator * 100.0D / denominator;
    }

    private Double deltaPct(long current, long previous) {
        if (previous <= 0) {
            return null;
        }
        return (current - previous) * 100.0D / previous;
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private Long toBucketKey(Date timestamp, TrendRange range) {
        if (timestamp == null) {
            return null;
        }
        Instant instant = timestamp.toInstant();
        if (instant.isBefore(range.start()) || !instant.isBefore(range.end())) {
            return null;
        }
        ZonedDateTime zonedDateTime = instant.atZone(range.zoneId());
        ZonedDateTime bucketStart = switch (range.granularity()) {
            case HOUR -> zonedDateTime.withMinute(0).withSecond(0).withNano(0);
            case DAY -> zonedDateTime.toLocalDate().atStartOfDay(range.zoneId());
        };
        return bucketStart.toInstant().toEpochMilli();
    }

    private Date toDate(Instant instant) {
        return Date.from(instant);
    }

    private Long toEpochMilli(Date date) {
        return date == null ? null : date.getTime();
    }

    private String summarizeContent(String content) {
        if (content == null || content.isBlank()) {
            return "暂无内容";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= CONTENT_SNIPPET_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, CONTENT_SNIPPET_LIMIT) + "...";
    }

    private String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private WindowRange resolveWindow(String window) {
        String normalized = normalize(window);
        Duration duration = switch (normalized) {
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(24);
        };
        String effectiveWindow = switch (normalized) {
            case "7d", "30d", "24h" -> normalized;
            default -> "24h";
        };
        Instant now = Instant.now();
        Instant currentStart = now.minus(duration);
        return new WindowRange(
                effectiveWindow,
                "previous-" + effectiveWindow,
                currentStart,
                now,
                currentStart.minus(duration),
                currentStart
        );
    }

    private TrendRange resolveTrendRange(String window, String granularity) {
        WindowRange windowRange = resolveWindow(window);
        BucketGranularity bucketGranularity = resolveGranularity(windowRange.window(), granularity);
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = Instant.now().atZone(zoneId);
        ZonedDateTime end = switch (bucketGranularity) {
            case HOUR -> now.withMinute(0).withSecond(0).withNano(0).plusHours(1);
            case DAY -> now.toLocalDate().atStartOfDay(zoneId).plusDays(1);
        };
        int bucketCount = switch (bucketGranularity) {
            case HOUR -> Math.max(1, (int) windowRange.duration().toHours());
            case DAY -> Math.max(1, (int) windowRange.duration().toDays());
        };
        ZonedDateTime start = switch (bucketGranularity) {
            case HOUR -> end.minusHours(bucketCount);
            case DAY -> end.minusDays(bucketCount);
        };

        List<Long> bucketKeys = new ArrayList<>(bucketCount);
        ZonedDateTime cursor = start;
        for (int i = 0; i < bucketCount; i++) {
            bucketKeys.add(cursor.toInstant().toEpochMilli());
            cursor = switch (bucketGranularity) {
                case HOUR -> cursor.plusHours(1);
                case DAY -> cursor.plusDays(1);
            };
        }

        return new TrendRange(
                windowRange.window(),
                bucketGranularity,
                zoneId,
                start.toInstant(),
                end.toInstant(),
                bucketKeys
        );
    }

    private BucketGranularity resolveGranularity(String window, String granularity) {
        String normalized = normalize(granularity);
        if ("hour".equals(normalized)) {
            return BucketGranularity.HOUR;
        }
        if ("day".equals(normalized)) {
            return BucketGranularity.DAY;
        }
        return "24h".equals(window) ? BucketGranularity.HOUR : BucketGranularity.DAY;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class AverageAccumulator {
        private long total;
        private long count;
    }

    private static final class RateAccumulator {
        private long total;
        private long matched;
    }

    private record WindowRange(String window,
                               String compareWindow,
                               Instant currentStart,
                               Instant currentEnd,
                               Instant previousStart,
                               Instant previousEnd) {

        private Duration duration() {
            return Duration.between(currentStart, currentEnd);
        }
    }

    private enum BucketGranularity {
        HOUR("hour"),
        DAY("day");

        private final String wireValue;

        BucketGranularity(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private record TrendRange(String window,
                              BucketGranularity granularity,
                              ZoneId zoneId,
                              Instant start,
                              Instant end,
                              List<Long> bucketKeys) {
    }
}
