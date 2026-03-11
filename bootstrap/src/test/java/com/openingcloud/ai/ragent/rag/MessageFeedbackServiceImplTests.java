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

package com.openingcloud.ai.ragent.rag;

import com.openingcloud.ai.ragent.framework.context.LoginUser;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.openingcloud.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.openingcloud.ai.ragent.rag.service.bo.MessageFeedbackDetailBO;
import com.openingcloud.ai.ragent.rag.service.impl.MessageFeedbackServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageFeedbackServiceImplTests {

    @Mock
    private MessageFeedbackMapper feedbackMapper;

    @Mock
    private ConversationMessageMapper conversationMessageMapper;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRejectUnsupportedDislikeReason() {
        UserContext.set(LoginUser.builder().userId("u-1").build());

        MessageFeedbackServiceImpl service = new MessageFeedbackServiceImpl(feedbackMapper, conversationMessageMapper);
        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setVote(-1);
        request.setReason("随便写一个原因");

        assertThrows(ClientException.class, () -> service.submitFeedback("101", request));
    }

    @Test
    void shouldClearReasonAndCommentForPositiveFeedbackAndExposeFeedbackDetails() {
        UserContext.set(LoginUser.builder().userId("u-1").build());
        when(conversationMessageMapper.selectOne(any())).thenReturn(assistantMessage());

        MessageFeedbackServiceImpl service = new MessageFeedbackServiceImpl(feedbackMapper, conversationMessageMapper);
        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setVote(1);
        request.setReason("检索材料不对");
        request.setComment("这条在点赞时应被忽略");

        service.submitFeedback("101", request);

        ArgumentCaptor<MessageFeedbackDO> captor = ArgumentCaptor.forClass(MessageFeedbackDO.class);
        verify(feedbackMapper).insert(captor.capture());
        MessageFeedbackDO inserted = captor.getValue();
        assertEquals(1, inserted.getVote());
        assertNull(inserted.getReason());
        assertNull(inserted.getComment());

        when(feedbackMapper.selectList(any())).thenReturn(List.of(
                MessageFeedbackDO.builder()
                        .messageId(101L)
                        .vote(-1)
                        .reason("路由判别错误")
                        .comment("应该命中别的知识库")
                        .build()
        ));

        Map<Long, MessageFeedbackDetailBO> details = service.getUserFeedbacks("u-1", List.of(101L));
        assertEquals(-1, details.get(101L).getVote());
        assertEquals("路由判别错误", details.get(101L).getReason());
        assertEquals("应该命中别的知识库", details.get(101L).getComment());
    }

    private ConversationMessageDO assistantMessage() {
        ConversationMessageDO message = new ConversationMessageDO();
        message.setId(101L);
        message.setConversationId("conv-1");
        message.setUserId("u-1");
        message.setRole("assistant");
        return message;
    }
}
