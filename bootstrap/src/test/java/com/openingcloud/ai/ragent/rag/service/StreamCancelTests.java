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

package com.openingcloud.ai.ragent.rag.service;

import com.openingcloud.ai.ragent.infra.chat.FirstPacketAwaiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamCancelTests {

    @Test
    void testFirstPacketAwaiterCancelViaError() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        RuntimeException ex = new RuntimeException("cancelled by user");
        awaiter.markError(ex);

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.ERROR, result.getType());
        assertSame(ex, result.getError());
        assertFalse(result.isSuccess());
    }

    @Test
    void testFirstPacketAwaiterNoContent() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markComplete();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.NO_CONTENT, result.getType());
        assertFalse(result.isSuccess());
        assertNull(result.getError());
    }

    @Test
    void testFirstPacketAwaiterOnlyFiresOnce() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();

        awaiter.markContent();
        awaiter.markContent();
        awaiter.markComplete();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(FirstPacketAwaiter.Result.Type.SUCCESS, result.getType());
    }

    @Test
    void testFirstPacketAwaiterConcurrentAccess() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        AtomicReference<FirstPacketAwaiter.Result> resultRef = new AtomicReference<>();
        CountDownLatch waitStarted = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try {
                waitStarted.countDown();
                FirstPacketAwaiter.Result r = awaiter.await(5, TimeUnit.SECONDS);
                resultRef.set(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        waitStarted.await(1, TimeUnit.SECONDS);
        Thread.sleep(50);
        awaiter.markContent();

        waiter.join(3000);
        assertNotNull(resultRef.get());
        assertTrue(resultRef.get().isSuccess());
    }

    @Test
    void testFirstPacketAwaiterErrorBeforeContent() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        RuntimeException ex = new RuntimeException("upstream error");
        awaiter.markError(ex);
        awaiter.markContent();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.ERROR, result.getType());
        assertSame(ex, result.getError());
    }
}
