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

package com.nageoffer.ai.ragent.ingestion.service;

import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.IntentBootstrapProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentTreeBootstrapServiceTests {

    @Mock
    private IntentNodeMapper intentNodeMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private IntentTreeBootstrapService service;

    private final AtomicLong activeNodeCount = new AtomicLong(0);
    private final List<IntentNodeDO> insertedNodes = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws InterruptedException {
        IntentBootstrapProperties properties = new IntentBootstrapProperties();
        properties.setEnabled(true);
        properties.setStrategy("from-existing-kb");
        properties.setIncludeSystem(true);

        service = new IntentTreeBootstrapService(
                intentNodeMapper,
                knowledgeBaseMapper,
                intentTreeCacheManager,
                redissonClient,
                properties
        );

        when(intentNodeMapper.selectCount(any())).thenAnswer(invocation -> activeNodeCount.get());
    }

    @Test
    void shouldCreateNodesWhenIntentTreeIsEmpty() throws Exception {
        stubLockAndInsertBehavior();
        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(2001L)
                .name("研发知识库")
                .collectionName("kb_research")
                .deleted(0)
                .build();
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb));

        int created = service.initializeManually();

        assertEquals(insertedNodes.size(), created);
        assertTrue(created >= 5);
        assertTrue(insertedNodes.stream().anyMatch(node ->
                Objects.equals(IntentKind.KB.getCode(), node.getKind())
                        && Objects.equals(IntentLevel.TOPIC.getCode(), node.getLevel())
                        && Long.valueOf(2001L).equals(node.getKbId())
                        && "kb_research".equals(node.getCollectionName())
        ));
        verify(intentTreeCacheManager).clearIntentTreeCache();
    }

    @Test
    void shouldNoopWhenIntentTreeAlreadyExists() {
        activeNodeCount.set(1);

        int created = service.initializeManually();

        assertEquals(0, created);
        verify(intentNodeMapper, never()).insert(org.mockito.ArgumentMatchers.<IntentNodeDO>any());
        verify(knowledgeBaseMapper, never()).selectList(any());
        verify(intentTreeCacheManager, never()).clearIntentTreeCache();
    }

    @Test
    void shouldRemainIdempotentUnderConcurrentInitialization() throws Exception {
        stubLockAndInsertBehavior();
        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(3001L)
                .name("并发测试知识库")
                .collectionName("kb_concurrent")
                .deleted(0)
                .build();
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb));

        Semaphore semaphore = new Semaphore(1);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenAnswer(invocation -> semaphore.tryAcquire());
        doAnswer(invocation -> {
            semaphore.release();
            return null;
        }).when(lock).unlock();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(service::initializeManually);
            Future<Integer> second = executor.submit(service::initializeManually);

            int createdFirst = first.get(5, TimeUnit.SECONDS);
            int createdSecond = second.get(5, TimeUnit.SECONDS);

            assertTrue(createdFirst > 0 || createdSecond > 0);
            assertTrue(createdFirst == 0 || createdSecond == 0);
            assertEquals(insertedNodes.size(), createdFirst + createdSecond);

            Set<String> uniqueCodes = insertedNodes.stream()
                    .map(IntentNodeDO::getIntentCode)
                    .collect(Collectors.toSet());
            assertEquals(insertedNodes.size(), uniqueCodes.size());
        } finally {
            executor.shutdownNow();
        }
    }

    private void stubLockAndInsertBehavior() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(intentNodeMapper.insert(org.mockito.ArgumentMatchers.<IntentNodeDO>any())).thenAnswer(invocation -> {
            IntentNodeDO node = invocation.getArgument(0);
            insertedNodes.add(node);
            activeNodeCount.incrementAndGet();
            return 1;
        });
    }
}
