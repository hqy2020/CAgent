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

package com.openingcloud.ai.ragent.rag.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolConfigTests {

    private ThreadPoolExecutorConfig config;

    @BeforeEach
    void setUp() {
        config = new ThreadPoolExecutorConfig();
    }

    @Test
    void testAllExecutorBeansExist() {
        assertNotNull(config.mcpBatchThreadPoolExecutor());
        assertNotNull(config.ragContextThreadPoolExecutor());
        assertNotNull(config.ragRetrievalThreadPoolExecutor());
        assertNotNull(config.ragInnerRetrievalThreadPoolExecutor());
        assertNotNull(config.intentClassifyThreadPoolExecutor());
        assertNotNull(config.memorySummaryThreadPoolExecutor());
        assertNotNull(config.modelStreamExecutor());
        assertNotNull(config.chatEntryExecutor());
        assertNotNull(config.knowledgeChunkExecutor());
    }

    @Test
    void testTtlWrappedExecutor() {
        Executor executor = config.ragRetrievalThreadPoolExecutor();
        String className = executor.getClass().getName();
        assertTrue(className.contains("Ttl") || className.contains("ttl"),
                "Executor should be wrapped by TtlExecutors, got: " + className);
    }

    @Test
    void testExecutorCanRunTask() throws InterruptedException {
        Executor executor = config.ragContextThreadPoolExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        executor.execute(() -> {
            ran.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(ran.get());
    }

    @Test
    void testCpuCountIsPositive() {
        assertTrue(ThreadPoolExecutorConfig.CPU_COUNT > 0);
    }
}
