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

import com.nageoffer.ai.ragent.rag.config.IntentBootstrapProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentTreeBootstrapServiceTests {

    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private IntentTreeService intentTreeService;
    @Mock
    private RLock lock;

    private IntentTreeBootstrapService service;

    @BeforeEach
    void setUp() {
        IntentBootstrapProperties properties = new IntentBootstrapProperties();
        properties.setEnabled(true);
        properties.setStrategy("from-existing-kb");
        properties.setIncludeSystem(true);

        service = new IntentTreeBootstrapService(
                intentTreeCacheManager,
                redissonClient,
                properties,
                intentTreeService
        );
    }

    @Test
    void shouldCreateNodesWhenIntentTreeIsEmpty() throws Exception {
        stubLockAndInsertBehavior();
        when(intentTreeService.initFromFactory()).thenReturn(5);

        int created = service.initializeManually();

        assertEquals(5, created);
        verify(intentTreeCacheManager).clearIntentTreeCache();
    }

    @Test
    void shouldSyncMissingNodesWhenIntentTreeAlreadyExists() throws Exception {
        stubLockAndInsertBehavior();
        when(intentTreeService.initFromFactory()).thenReturn(2);

        int created = service.initializeManually();

        assertEquals(2, created);
        verify(intentTreeService).initFromFactory();
        verify(intentTreeCacheManager).clearIntentTreeCache();
    }

    @Test
    void shouldNotClearCacheWhenNoNodesCreated() throws Exception {
        stubLockAndInsertBehavior();
        when(intentTreeService.initFromFactory()).thenReturn(0);

        int created = service.initializeManually();

        assertEquals(0, created);
        verify(intentTreeService).initFromFactory();
        verify(intentTreeCacheManager, never()).clearIntentTreeCache();
    }

    @Test
    void shouldRemainIdempotentUnderConcurrentInitialization() throws Exception {
        stubLockAndInsertBehavior();
        when(intentTreeService.initFromFactory()).thenReturn(5, 0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(service::initializeManually);
            Future<Integer> second = executor.submit(service::initializeManually);

            int createdFirst = first.get(5, TimeUnit.SECONDS);
            int createdSecond = second.get(5, TimeUnit.SECONDS);

            assertTrue(createdFirst > 0 || createdSecond > 0);
            assertTrue(createdFirst == 0 || createdSecond == 0);
            verify(intentTreeService, times(2)).initFromFactory();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSyncFactoryDefinitionsManually() throws Exception {
        stubLockAndInsertBehavior();
        when(intentTreeService.syncFromFactory()).thenReturn(new IntentTreeSyncResult(0, 2, 1));

        IntentTreeSyncResult result = service.syncManually();

        assertEquals(0, result.created());
        assertEquals(2, result.updated());
        assertEquals(1, result.repaired());
        verify(intentTreeService).syncFromFactory();
        verify(intentTreeCacheManager).clearIntentTreeCache();
    }

    private void stubLockAndInsertBehavior() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }
}
