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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.mq.event.IngestionExecuteEvent;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionExecuteConsumerTests {

    @Mock
    private IngestionEngine engine;
    @Mock
    private IngestionPipelineService pipelineService;
    @Mock
    private IngestionTaskServiceImpl taskService;
    @Mock
    private IngestionTaskMapper taskMapper;

    @Test
    void testShouldInjectMetadataIntoContext() {
        IngestionExecuteConsumer consumer = new IngestionExecuteConsumer(
                engine,
                pipelineService,
                taskService,
                taskMapper,
                new ObjectMapper()
        );

        IngestionExecuteEvent event = IngestionExecuteEvent.builder()
                .messageId("m1")
                .taskId("1")
                .pipelineId("10")
                .sourceType("url")
                .sourceLocation("https://example.com/doc.pdf")
                .operator("tester")
                .metadataJson("{\"owner\":\"qa\",\"priority\":1}")
                .build();

        IngestionTaskDO task = IngestionTaskDO.builder().id(1L).pipelineId(10L).build();
        when(taskMapper.selectById("1")).thenReturn(task);
        when(pipelineService.getDefinition("10")).thenReturn(PipelineDefinition.builder()
                .id("10")
                .nodes(List.of())
                .build());
        when(engine.execute(any(), any())).thenAnswer(invocation -> {
            IngestionContext context = invocation.getArgument(1);
            context.setStatus(IngestionStatus.COMPLETED);
            return context;
        });

        consumer.onMessage(event);

        ArgumentCaptor<IngestionContext> contextCaptor = ArgumentCaptor.forClass(IngestionContext.class);
        verify(engine).execute(any(), contextCaptor.capture());
        IngestionContext context = contextCaptor.getValue();
        assertNotNull(context.getMetadata());
        assertEquals("qa", context.getMetadata().get("owner"));
        assertEquals(1, ((Number) context.getMetadata().get("priority")).intValue());
    }
}
