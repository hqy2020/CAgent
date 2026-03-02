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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.chat.FirstPacketAwaiter;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ModelRoutingTests {

    private ModelHealthStore createStore(int failureThreshold, long openDurationMs) {
        AIModelProperties props = new AIModelProperties();
        AIModelProperties.Selection selection = new AIModelProperties.Selection();
        selection.setFailureThreshold(failureThreshold);
        selection.setOpenDurationMs(openDurationMs);
        props.setSelection(selection);
        return new ModelHealthStore(props);
    }

    @Test
    void testCircuitBreakerClosedToOpen() {
        ModelHealthStore store = createStore(2, 5000L);
        String modelId = "model-a";

        assertTrue(store.allowCall(modelId));
        assertFalse(store.isOpen(modelId));

        store.markFailure(modelId);
        assertFalse(store.isOpen(modelId));

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));
        assertFalse(store.allowCall(modelId));
    }

    @Test
    void testCircuitBreakerOpenToHalfOpenToClosed() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-b";

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        Thread.sleep(80);

        assertFalse(store.isOpen(modelId));
        assertTrue(store.allowCall(modelId));

        store.markSuccess(modelId);
        assertFalse(store.isOpen(modelId));
        assertTrue(store.allowCall(modelId));
    }

    @Test
    void testCircuitBreakerHalfOpenFailureReOpens() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-c";

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        Thread.sleep(80);
        assertTrue(store.allowCall(modelId));

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));
    }

    @Test
    void testFirstPacketAwaiterSuccess() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markContent();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(FirstPacketAwaiter.Result.Type.SUCCESS, result.getType());
    }

    @Test
    void testFirstPacketAwaiterTimeout() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();

        FirstPacketAwaiter.Result result = awaiter.await(50, TimeUnit.MILLISECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.TIMEOUT, result.getType());
        assertFalse(result.isSuccess());
    }

    @Test
    void testFirstPacketAwaiterError() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        RuntimeException ex = new RuntimeException("connection refused");
        awaiter.markError(ex);

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.ERROR, result.getType());
        assertSame(ex, result.getError());
    }

    @Test
    void testFirstPacketAwaiterNoContent() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markComplete();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.NO_CONTENT, result.getType());
    }

    @Test
    void testAllowCallReturnsFalseForNull() {
        ModelHealthStore store = createStore(2, 5000L);
        assertFalse(store.allowCall(null));
    }

    @Test
    void testMarkSuccessResetState() {
        ModelHealthStore store = createStore(2, 5000L);
        String modelId = "model-d";

        store.markFailure(modelId);
        store.markSuccess(modelId);

        store.markFailure(modelId);
        assertFalse(store.isOpen(modelId));
    }
}
