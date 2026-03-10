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

package com.openingcloud.ai.ragent.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.context.LoginUser;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.mq.constant.MQConstant;
import com.openingcloud.ai.ragent.framework.mq.event.IngestionExecuteEvent;
import com.openingcloud.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.openingcloud.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.openingcloud.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.openingcloud.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.openingcloud.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.openingcloud.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.openingcloud.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.openingcloud.ai.ragent.ingestion.domain.enums.SourceType;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.openingcloud.ai.ragent.ingestion.engine.IngestionEngine;
import com.openingcloud.ai.ragent.ingestion.service.impl.IngestionTaskServiceImpl;
import com.openingcloud.ai.ragent.rag.controller.request.DocumentSourceRequest;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionTaskServiceImplTests {

    @Mock
    private IngestionEngine engine;
    @Mock
    private IngestionPipelineService pipelineService;
    @Mock
    private IngestionTaskMapper taskMapper;
    @Mock
    private IngestionTaskNodeMapper taskNodeMapper;
    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private IngestionTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IngestionTaskServiceImpl(
                engine,
                pipelineService,
                taskMapper,
                taskNodeMapper,
                new ObjectMapper(),
                rocketMQTemplate
        );
        UserContext.set(LoginUser.builder().username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testGetShouldParseMetadataJson() {
        IngestionTaskDO task = IngestionTaskDO.builder()
                .id(1L)
                .pipelineId(100L)
                .status("completed")
                .metadataJson("{\"category\":\"manual\",\"version\":2}")
                .build();
        when(taskMapper.selectById("1")).thenReturn(task);

        IngestionTaskVO vo = service.get("1");

        assertNotNull(vo.getMetadata());
        assertEquals("manual", vo.getMetadata().get("category"));
        assertEquals(2, ((Number) vo.getMetadata().get("version")).intValue());
    }

    @Test
    void testListNodesShouldParseOutputJson() {
        IngestionTaskNodeDO node = IngestionTaskNodeDO.builder()
                .id(10L)
                .taskId(1L)
                .pipelineId(100L)
                .nodeId("chunker-1")
                .nodeType("chunker")
                .status("success")
                .outputJson("{\"chunkCount\":3,\"mimeType\":\"application/pdf\"}")
                .build();
        when(taskNodeMapper.selectList(any())).thenReturn(List.of(node));

        List<IngestionTaskNodeVO> nodes = service.listNodes("1");

        assertEquals(1, nodes.size());
        assertNotNull(nodes.get(0).getOutput());
        assertEquals(3, ((Number) nodes.get(0).getOutput().get("chunkCount")).intValue());
        assertEquals("application/pdf", nodes.get(0).getOutput().get("mimeType"));
    }

    @Test
    void testExecuteShouldSendMetadataJsonInEvent() {
        when(taskMapper.insert(any(IngestionTaskDO.class))).thenAnswer(invocation -> {
            IngestionTaskDO task = invocation.getArgument(0);
            task.setId(123L);
            return 1;
        });
        when(pipelineService.getDefinition("10")).thenReturn(PipelineDefinition.builder()
                .id("10")
                .name("demo")
                .nodes(List.of())
                .build());

        IngestionTaskCreateRequest request = new IngestionTaskCreateRequest();
        request.setPipelineId("10");
        request.setMetadata(Map.of("owner", "qa"));
        DocumentSourceRequest source = new DocumentSourceRequest();
        source.setType(SourceType.URL);
        source.setLocation("https://example.com/doc.pdf");
        request.setSource(source);

        service.execute(request);

        ArgumentCaptor<IngestionExecuteEvent> eventCaptor = ArgumentCaptor.forClass(IngestionExecuteEvent.class);
        verify(rocketMQTemplate).syncSend(eq(MQConstant.TOPIC_INGESTION_EXECUTE), eventCaptor.capture());
        assertEquals("{\"owner\":\"qa\"}", eventCaptor.getValue().getMetadataJson());
    }
}
