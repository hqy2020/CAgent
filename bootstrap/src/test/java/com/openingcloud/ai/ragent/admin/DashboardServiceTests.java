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

package com.openingcloud.ai.ragent.admin;

import com.openingcloud.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardPerformanceVO;
import com.openingcloud.ai.ragent.admin.controller.vo.DashboardTrendsVO;
import com.openingcloud.ai.ragent.admin.service.DashboardService;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.openingcloud.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.openingcloud.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.openingcloud.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTests {

    @Mock
    private UserMapper userMapper;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private ConversationMessageMapper conversationMessageMapper;

    @Mock
    private RagTraceRunMapper ragTraceRunMapper;

    @Mock
    private MessageFeedbackMapper messageFeedbackMapper;

    @Test
    void shouldBuildOverviewFromCurrentAndPreviousWindow() {
        DashboardService service = new DashboardService(
                userMapper,
                conversationMapper,
                conversationMessageMapper,
                ragTraceRunMapper,
                messageFeedbackMapper
        );

        Instant now = Instant.now();
        List<ConversationMessageDO> currentMessages = List.of(
                message("conv-a", "u-1", "user", "你好", now.minus(Duration.ofHours(1))),
                message("conv-a", "u-1", "assistant", "回答 1", now.minus(Duration.ofHours(1)).plusSeconds(10)),
                message("conv-b", "u-2", "user", "问题 2", now.minus(Duration.ofHours(2))),
                message("conv-b", "u-2", "assistant", "回答 2", now.minus(Duration.ofHours(2)).plusSeconds(20))
        );
        List<ConversationMessageDO> previousMessages = List.of(
                message("conv-c", "u-3", "user", "历史问题", now.minus(Duration.ofHours(26))),
                message("conv-c", "u-3", "assistant", "历史回答", now.minus(Duration.ofHours(26)).plusSeconds(15)),
                message("conv-d", "u-3", "assistant", "历史回答 2", now.minus(Duration.ofHours(27)))
        );
        List<MessageFeedbackDO> currentFeedback = List.of(
                feedback(11L, "conv-a", 1, null, null, now.minus(Duration.ofHours(1))),
                feedback(12L, "conv-b", -1, "检索材料不对", "答案引用错文档", now.minus(Duration.ofMinutes(40)))
        );
        List<ConversationMessageDO> feedbackMessages = List.of(
                message(11L, "conv-a", "u-1", "assistant", "回答 1", now.minus(Duration.ofHours(1)).plusSeconds(10)),
                message(12L, "conv-b", "u-2", "assistant", "回答 2", now.minus(Duration.ofHours(2)).plusSeconds(20))
        );

        when(userMapper.selectCount(any())).thenReturn(10L, 8L);
        when(conversationMapper.selectCount(any())).thenReturn(20L, 17L);
        when(conversationMessageMapper.selectCount(any())).thenReturn(50L, 40L);
        when(conversationMessageMapper.selectList(any())).thenReturn(currentMessages, previousMessages, feedbackMessages);
        when(conversationMessageMapper.selectOne(any())).thenReturn(
                message("conv-b", "u-2", "user", "为什么资料和问题不对应？", now.minus(Duration.ofHours(2)))
        );
        when(messageFeedbackMapper.selectList(any())).thenReturn(currentFeedback, currentFeedback);

        DashboardOverviewVO overview = service.getOverview("24h");

        assertNotNull(overview);
        assertEquals("24h", overview.getWindow());
        assertEquals(10L, overview.getKpis().getTotalUsers().getValue());
        assertEquals(2L, overview.getKpis().getTotalUsers().getDelta());
        assertEquals(2L, overview.getKpis().getActiveUsers().getValue());
        assertEquals(100.0D, overview.getKpis().getActiveUsers().getDeltaPct());
        assertEquals(2L, overview.getKpis().getSessions24h().getValue());
        assertEquals(4L, overview.getKpis().getMessages24h().getValue());
        assertEquals(33.333333333333336D, overview.getKpis().getMessages24h().getDeltaPct());
        assertEquals(2L, overview.getFeedbackSummary().getTotalCount());
        assertEquals(50.0D, overview.getFeedbackSummary().getSatisfactionRate());
        assertEquals(1L, overview.getFeedbackSummary().getRecentNegativeCount24h());
        assertEquals(1, overview.getFeedbackSummary().getTopReasons().size());
        assertEquals("检索材料不对", overview.getFeedbackSummary().getTopReasons().get(0).getReason());
        assertEquals(1, overview.getNegativeSamples().size());
        assertEquals("为什么资料和问题不对应？", overview.getNegativeSamples().get(0).getQuestion());
    }

    @Test
    void shouldBuildPerformanceAndMessageTrend() {
        DashboardService service = new DashboardService(
                userMapper,
                conversationMapper,
                conversationMessageMapper,
                ragTraceRunMapper,
                messageFeedbackMapper
        );

        Instant now = Instant.now();
        List<RagTraceRunDO> runs = List.of(
                trace("SUCCESS", 1_000L, now.minus(Duration.ofMinutes(30))),
                trace("ERROR", 20_000L, now.minus(Duration.ofHours(2))),
                trace("SUCCESS", 5_000L, now.minus(Duration.ofHours(3))),
                trace("SUCCESS", 25_000L, now.minus(Duration.ofHours(4)))
        );
        List<ConversationMessageDO> performanceMessages = List.of(
                message("conv-a", "u-1", "assistant", "正常回答", now.minus(Duration.ofMinutes(30))),
                message("conv-b", "u-2", "assistant", "未检索到与问题相关的文档内容。", now.minus(Duration.ofHours(1))),
                message("conv-c", "u-3", "assistant", "正常回答", now.minus(Duration.ofHours(2))),
                message("conv-d", "u-4", "assistant", "正常回答", now.minus(Duration.ofHours(3)))
        );
        List<ConversationMessageDO> trendMessages = List.of(
                message("conv-a", "u-1", "user", "q1", now.minus(Duration.ofMinutes(10))),
                message("conv-a", "u-1", "assistant", "a1", now.minus(Duration.ofMinutes(9))),
                message("conv-b", "u-2", "user", "q2", now.minus(Duration.ofHours(1)).plus(Duration.ofMinutes(5)))
        );

        when(ragTraceRunMapper.selectList(any())).thenReturn(runs, List.of());
        when(conversationMessageMapper.selectList(any())).thenReturn(performanceMessages, trendMessages);

        DashboardPerformanceVO performance = service.getPerformance("24h");
        DashboardTrendsVO messageTrends = service.getTrends("messages", "24h", "hour");

        assertEquals(75.0D, performance.getSuccessRate());
        assertEquals(25.0D, performance.getErrorRate());
        assertEquals(25.0D, performance.getNoDocRate());
        assertEquals(25.0D, performance.getSlowRate());
        assertEquals(12_750.0D, performance.getAvgLatencyMs());
        assertEquals(25_000.0D, performance.getP95LatencyMs());

        assertEquals("messages", messageTrends.getMetric());
        assertEquals(1, messageTrends.getSeries().size());
        assertEquals(24, messageTrends.getSeries().get(0).getData().size());
        double total = messageTrends.getSeries().get(0).getData().stream()
                .mapToDouble(point -> point.getValue() == null ? 0D : point.getValue())
                .sum();
        long nonZeroBuckets = messageTrends.getSeries().get(0).getData().stream()
                .filter(point -> point.getValue() != null && point.getValue() > 0)
                .count();
        assertEquals(3.0D, total);
        assertTrue(nonZeroBuckets >= 2);
    }

    private ConversationMessageDO message(String conversationId,
                                          String userId,
                                          String role,
                                          String content,
                                          Instant createTime) {
        return message(null, conversationId, userId, role, content, createTime);
    }

    private ConversationMessageDO message(Long id,
                                          String conversationId,
                                          String userId,
                                          String role,
                                          String content,
                                          Instant createTime) {
        ConversationMessageDO message = new ConversationMessageDO();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(Date.from(createTime));
        return message;
    }

    private MessageFeedbackDO feedback(Long messageId,
                                       String conversationId,
                                       Integer vote,
                                       String reason,
                                       String comment,
                                       Instant createTime) {
        MessageFeedbackDO feedback = new MessageFeedbackDO();
        feedback.setMessageId(messageId);
        feedback.setConversationId(conversationId);
        feedback.setVote(vote);
        feedback.setReason(reason);
        feedback.setComment(comment);
        feedback.setCreateTime(Date.from(createTime));
        return feedback;
    }

    private RagTraceRunDO trace(String status, long durationMs, Instant startTime) {
        RagTraceRunDO traceRun = new RagTraceRunDO();
        traceRun.setTraceName("rag-stream-chat");
        traceRun.setStatus(status);
        traceRun.setDurationMs(durationMs);
        traceRun.setStartTime(Date.from(startTime));
        return traceRun;
    }
}
