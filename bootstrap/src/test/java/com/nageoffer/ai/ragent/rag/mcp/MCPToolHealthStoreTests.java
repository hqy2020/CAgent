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

package com.nageoffer.ai.ragent.rag.mcp;

import com.nageoffer.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPToolHealthStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPToolHealthStoreTests {

    @Test
    void shouldOpenAndRecoverThroughHalfOpen() throws Exception {
        RagMcpExecutionProperties properties = new RagMcpExecutionProperties();
        properties.getCircuitBreaker().setFailureThreshold(2);
        properties.getCircuitBreaker().setOpenDurationMs(20);
        properties.getCircuitBreaker().setMaxOpenDurationMs(100);

        MCPToolHealthStore store = new MCPToolHealthStore(properties);

        assertTrue(store.allowCall("tool-a"));
        store.markFailure("tool-a");
        assertEquals("CLOSED", store.currentState("tool-a"));

        store.markFailure("tool-a");
        assertTrue(store.isOpen("tool-a"));
        assertEquals("OPEN", store.currentState("tool-a"));
        assertFalse(store.allowCall("tool-a"));

        Thread.sleep(25);
        assertTrue(store.allowCall("tool-a"));
        assertEquals("HALF_OPEN", store.currentState("tool-a"));

        store.markSuccess("tool-a");
        assertEquals("CLOSED", store.currentState("tool-a"));
        assertTrue(store.allowCall("tool-a"));
    }

    @Test
    void shouldUseExponentialBackoffAfterHalfOpenFailure() throws Exception {
        RagMcpExecutionProperties properties = new RagMcpExecutionProperties();
        properties.getCircuitBreaker().setFailureThreshold(1);
        properties.getCircuitBreaker().setOpenDurationMs(20);
        properties.getCircuitBreaker().setMaxOpenDurationMs(200);

        MCPToolHealthStore store = new MCPToolHealthStore(properties);

        store.markFailure("tool-b");
        assertTrue(store.isOpen("tool-b"));
        Thread.sleep(25);

        assertTrue(store.allowCall("tool-b"));
        assertEquals("HALF_OPEN", store.currentState("tool-b"));
        store.markFailure("tool-b");
        assertEquals("OPEN", store.currentState("tool-b"));

        Thread.sleep(25);
        assertFalse(store.allowCall("tool-b"));
        assertEquals("OPEN", store.currentState("tool-b"));
    }
}
