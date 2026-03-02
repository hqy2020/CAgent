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

package com.nageoffer.ai.ragent.rag.aop;

import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatQueueLimiterTests {

    @Mock
    private RedissonClient redissonClient;

    @Test
    void testRateLimitPropertiesDefaults() {
        RAGRateLimitProperties props = new RAGRateLimitProperties();

        assertNull(props.getGlobalEnabled());
        assertNull(props.getGlobalMaxConcurrent());
        assertNull(props.getGlobalMaxWaitSeconds());
        assertNull(props.getGlobalLeaseSeconds());
        assertNull(props.getGlobalPollIntervalMs());
    }

    @Test
    void testRateLimitPropertiesCustomValues() {
        RAGRateLimitProperties props = new RAGRateLimitProperties();
        props.setGlobalEnabled(true);
        props.setGlobalMaxConcurrent(100);
        props.setGlobalMaxWaitSeconds(30);
        props.setGlobalLeaseSeconds(300);
        props.setGlobalPollIntervalMs(100);

        assertTrue(props.getGlobalEnabled());
        assertEquals(100, props.getGlobalMaxConcurrent());
        assertEquals(30, props.getGlobalMaxWaitSeconds());
        assertEquals(300, props.getGlobalLeaseSeconds());
        assertEquals(100, props.getGlobalPollIntervalMs());
    }

    @Test
    void testDisabledLimiterExecutesDirectly() throws Exception {
        RAGRateLimitProperties props = new RAGRateLimitProperties();
        props.setGlobalEnabled(false);
        props.setGlobalMaxConcurrent(50);
        props.setGlobalMaxWaitSeconds(20);
        props.setGlobalLeaseSeconds(600);
        props.setGlobalPollIntervalMs(200);

        RTopic topic = mock(RTopic.class);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);
        when(topic.addListener(any(Class.class), any())).thenReturn(1);

        AtomicBoolean executed = new AtomicBoolean(false);
        Executor syncExecutor = Runnable::run;

        ChatQueueLimiter limiter = new ChatQueueLimiter(
                redissonClient, props, null, null, null, syncExecutor
        );
        limiter.subscribeQueueNotify();

        limiter.enqueue("test question", "conv-1", null, () -> executed.set(true));

        assertTrue(executed.get());
        limiter.shutdown();
    }

    @Test
    void testEnabledLimiterAttemptsAcquire() throws Exception {
        RAGRateLimitProperties props = new RAGRateLimitProperties();
        props.setGlobalEnabled(true);
        props.setGlobalMaxConcurrent(2);
        props.setGlobalMaxWaitSeconds(1);
        props.setGlobalLeaseSeconds(60);
        props.setGlobalPollIntervalMs(50);

        RTopic topic = mock(RTopic.class);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);
        when(topic.addListener(any(Class.class), any())).thenReturn(1);

        Executor syncExecutor = Runnable::run;

        ChatQueueLimiter limiter = new ChatQueueLimiter(
                redissonClient, props, null, null, null, syncExecutor
        );
        limiter.subscribeQueueNotify();

        verify(redissonClient).getTopic(anyString());
        limiter.shutdown();
    }
}
