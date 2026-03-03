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

package com.nageoffer.ai.ragent.knowledge.mq.consumer;

import com.nageoffer.ai.ragent.framework.idempotent.IdempotentConsume;
import com.nageoffer.ai.ragent.framework.mq.constant.MQConstant;
import com.nageoffer.ai.ragent.framework.mq.event.ScheduleRefreshEvent;
import com.nageoffer.ai.ragent.knowledge.schedule.KnowledgeDocumentScheduleJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MQConstant.TOPIC_KNOWLEDGE_SCHEDULE_REFRESH,
        consumerGroup = MQConstant.CG_KNOWLEDGE_SCHEDULE_REFRESH,
        consumeThreadNumber = 2
)
public class ScheduleRefreshConsumer implements RocketMQListener<ScheduleRefreshEvent> {

    private final KnowledgeDocumentScheduleJob scheduleJob;

    @Override
    @IdempotentConsume(
            keyPrefix = MQConstant.IDEM_PREFIX_SCHEDULE,
            key = "#event.messageId",
            keyTimeout = 3600
    )
    public void onMessage(ScheduleRefreshEvent event) {
        log.info("收到定时刷新消息: scheduleId={}, docId={}",
                event.getScheduleId(), event.getDocId());
        scheduleJob.executeSchedule(Long.parseLong(event.getScheduleId()));
        log.info("定时刷新消费完成: scheduleId={}", event.getScheduleId());
    }
}
