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

package com.nageoffer.ai.ragent.ingestion.mq.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentConsume;
import com.nageoffer.ai.ragent.framework.mq.constant.MQConstant;
import com.nageoffer.ai.ragent.framework.mq.event.IngestionExecuteEvent;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.impl.IngestionTaskServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MQConstant.TOPIC_INGESTION_EXECUTE,
        consumerGroup = MQConstant.CG_INGESTION_EXECUTE,
        consumeThreadNumber = 2
)
public class IngestionExecuteConsumer implements RocketMQListener<IngestionExecuteEvent> {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskServiceImpl taskService;
    private final IngestionTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    @Override
    @IdempotentConsume(
            keyPrefix = MQConstant.IDEM_PREFIX_INGESTION,
            key = "#event.messageId",
            keyTimeout = 7200
    )
    public void onMessage(IngestionExecuteEvent event) {
        log.info("收到 Ingestion 执行消息: taskId={}, pipelineId={}",
                event.getTaskId(), event.getPipelineId());
        try {
            UserContext.set(LoginUser.builder().username(event.getOperator()).build());

            IngestionTaskDO task = taskMapper.selectById(event.getTaskId());
            if (task == null) {
                log.warn("Ingestion 消费跳过：任务不存在, taskId={}", event.getTaskId());
                return;
            }

            task.setStatus(IngestionStatus.RUNNING.getValue());
            task.setStartedAt(new Date());
            task.setUpdatedBy(event.getOperator());
            taskMapper.updateById(task);

            PipelineDefinition pipeline = pipelineService.getDefinition(event.getPipelineId());

            DocumentSource source = DocumentSource.builder()
                    .type(StringUtils.hasText(event.getSourceType()) ? SourceType.fromValue(event.getSourceType()) : null)
                    .location(event.getSourceLocation())
                    .fileName(event.getSourceFileName())
                    .credentials(parseCredentials(event.getCredentialsJson()))
                    .build();

            byte[] rawBytes = null;
            if (StringUtils.hasText(event.getRawBytesBase64())) {
                rawBytes = Base64.getDecoder().decode(event.getRawBytesBase64());
            }

            VectorSpaceId vectorSpaceId = null;
            if (StringUtils.hasText(event.getVectorSpaceLogicalName())) {
                vectorSpaceId = VectorSpaceId.builder()
                        .logicalName(event.getVectorSpaceLogicalName())
                        .build();
            }

            IngestionContext context = IngestionContext.builder()
                    .taskId(event.getTaskId())
                    .pipelineId(event.getPipelineId())
                    .source(source)
                    .rawBytes(rawBytes)
                    .mimeType(event.getMimeType())
                    .vectorSpaceId(vectorSpaceId)
                    .metadata(parseMetadata(event.getMetadataJson()))
                    .logs(new ArrayList<>())
                    .build();

            IngestionContext result = engine.execute(pipeline, context);
            taskService.saveNodeLogs(task, pipeline, result.getLogs());
            taskService.updateTaskFromContext(task, result);

            log.info("Ingestion 消费完成: taskId={}, status={}",
                    event.getTaskId(), result.getStatus());
        } catch (Exception e) {
            log.error("Ingestion 消费异常: taskId={}", event.getTaskId(), e);
            markTaskFailed(event.getTaskId(), e.getMessage());
            throw e;
        } finally {
            UserContext.clear();
        }
    }

    private void markTaskFailed(String taskId, String errorMessage) {
        try {
            IngestionTaskDO update = new IngestionTaskDO();
            update.setId(Long.parseLong(taskId));
            update.setStatus(IngestionStatus.FAILED.getValue());
            update.setErrorMessage(errorMessage != null && errorMessage.length() > 500
                    ? errorMessage.substring(0, 500) : errorMessage);
            update.setCompletedAt(new Date());
            taskMapper.updateById(update);
        } catch (Exception ex) {
            log.error("标记 Ingestion 任务失败时出错: taskId={}", taskId, ex);
        }
    }

    private Map<String, String> parseCredentials(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("凭证 JSON 解析失败: {}", json, e);
            return null;
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("metadata JSON 解析失败: {}", json, e);
            return new HashMap<>();
        }
    }
}
