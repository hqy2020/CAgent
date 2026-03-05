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

package com.nageoffer.ai.ragent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.framework.mq.constant.MQConstant;
import com.nageoffer.ai.ragent.framework.mq.event.DocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceImplTests {

    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private KnowledgeDocumentMapper docMapper;
    @Mock
    private DocumentParserSelector parserSelector;
    @Mock
    private ChunkingStrategyFactory chunkingStrategyFactory;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private KnowledgeChunkService knowledgeChunkService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private HttpClientHelper httpClientHelper;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private KnowledgeDocumentScheduleService scheduleService;
    @Mock
    private IngestionPipelineService ingestionPipelineService;
    @Mock
    private IngestionPipelineMapper ingestionPipelineMapper;
    @Mock
    private IngestionEngine ingestionEngine;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Mock
    private Executor knowledgeChunkExecutor;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() throws InterruptedException {
        ReflectionTestUtils.setField(service, "runningStaleMinutes", 10L);

        RLock lock = org.mockito.Mockito.mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void testStartChunkShouldRecoverStaleRunningDocument() {
        KnowledgeDocumentDO staleRunning = KnowledgeDocumentDO.builder()
                .id(1L)
                .kbId(10L)
                .status(DocumentStatus.RUNNING.getCode())
                .updateTime(new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11)))
                .build();

        when(docMapper.selectById("1")).thenReturn(staleRunning);
        when(knowledgeChunkService.existsByDocId("1")).thenReturn(false);

        service.startChunk("1");

        verify(docMapper, atLeastOnce()).updateById(org.mockito.ArgumentMatchers.<KnowledgeDocumentDO>argThat(doc ->
                DocumentStatus.FAILED.getCode().equals(doc.getStatus())));
        verify(rocketMQTemplate).asyncSend(org.mockito.ArgumentMatchers.eq(MQConstant.TOPIC_KNOWLEDGE_CHUNK),
                any(DocumentChunkEvent.class), any(SendCallback.class));
    }
}
