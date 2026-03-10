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

package com.openingcloud.ai.ragent.knowledge.mq.consumer;

import com.openingcloud.ai.ragent.framework.context.LoginUser;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.exception.ServiceException;
import com.openingcloud.ai.ragent.framework.idempotent.IdempotentConsume;
import com.openingcloud.ai.ragent.framework.mq.constant.MQConstant;
import com.openingcloud.ai.ragent.framework.mq.event.DocumentChunkEvent;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MQConstant.TOPIC_KNOWLEDGE_CHUNK,
        consumerGroup = MQConstant.CG_KNOWLEDGE_CHUNK,
        consumeThreadNumber = 4
)
public class DocumentChunkConsumer implements RocketMQListener<DocumentChunkEvent> {

    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeDocumentServiceImpl documentService;

    @Override
    @IdempotentConsume(
            keyPrefix = MQConstant.IDEM_PREFIX_CHUNK,
            key = "#event.messageId",
            keyTimeout = 7200
    )
    public void onMessage(DocumentChunkEvent event) {
        log.info("收到分块消息: docId={}, messageId={}, triggerSource={}",
                event.getDocId(), event.getMessageId(), event.getTriggerSource());
        try {
            UserContext.set(LoginUser.builder().username(event.getOperator()).build());

            KnowledgeDocumentDO documentDO = docMapper.selectById(event.getDocId());
            if (documentDO == null) {
                log.warn("分块消费跳过：文档不存在, docId={}", event.getDocId());
                return;
            }

            documentService.runChunkTask(documentDO);
            log.info("分块消费完成: docId={}", event.getDocId());
        } catch (ServiceException e) {
            log.error("分块消费业务异常（不重试）: docId={}", event.getDocId(), e);
        } catch (Exception e) {
            log.error("分块消费异常（触发重试）: docId={}", event.getDocId(), e);
            throw e;
        } finally {
            UserContext.clear();
        }
    }
}
