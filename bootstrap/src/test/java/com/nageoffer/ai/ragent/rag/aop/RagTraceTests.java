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

import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceRoot;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagTraceTests {

    @Mock
    private RagTraceRecordService traceRecordService;

    @AfterEach
    void cleanup() {
        RagTraceContext.clear();
    }

    @Test
    void testContextPushPopAndDepth() {
        RagTraceContext.setTraceId("trace-001");
        assertEquals("trace-001", RagTraceContext.getTraceId());
        assertEquals(0, RagTraceContext.depth());
        assertNull(RagTraceContext.currentNodeId());

        RagTraceContext.pushNode("node-1");
        assertEquals(1, RagTraceContext.depth());
        assertEquals("node-1", RagTraceContext.currentNodeId());

        RagTraceContext.pushNode("node-2");
        assertEquals(2, RagTraceContext.depth());
        assertEquals("node-2", RagTraceContext.currentNodeId());

        RagTraceContext.popNode();
        assertEquals(1, RagTraceContext.depth());
        assertEquals("node-1", RagTraceContext.currentNodeId());

        RagTraceContext.popNode();
        assertEquals(0, RagTraceContext.depth());
        assertNull(RagTraceContext.currentNodeId());
    }

    @Test
    void testContextClearRemovesAll() {
        RagTraceContext.setTraceId("trace-002");
        RagTraceContext.setTaskId("task-002");
        RagTraceContext.pushNode("node-x");

        RagTraceContext.clear();

        assertNull(RagTraceContext.getTraceId());
        assertNull(RagTraceContext.getTaskId());
        assertNull(RagTraceContext.currentNodeId());
        assertEquals(0, RagTraceContext.depth());
    }

    @Test
    void testRootAspectDisabledSkipsTrace() throws Throwable {
        RagTraceProperties props = new RagTraceProperties();
        props.setEnabled(false);

        RagTraceAspect aspect = new RagTraceAspect(traceRecordService, props);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("result");

        RagTraceRoot traceRoot = mock(RagTraceRoot.class);

        Object result = aspect.aroundRoot(joinPoint, traceRoot);

        assertEquals("result", result);
        verify(traceRecordService, never()).startRun(any());
        verify(traceRecordService, never()).finishRun(any(), any(), any(), any(), anyLong());
    }

    @Test
    void testRootAspectRecordsOnException() throws Throwable {
        RagTraceProperties props = new RagTraceProperties();
        props.setEnabled(true);
        props.setMaxErrorLength(200);

        RagTraceAspect aspect = new RagTraceAspect(traceRecordService, props);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(Object.class.getMethod("toString"));
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenThrow(new RuntimeException("test error"));

        RagTraceRoot traceRoot = mock(RagTraceRoot.class);
        when(traceRoot.name()).thenReturn("test-root");
        when(traceRoot.conversationIdArg()).thenReturn("");
        when(traceRoot.taskIdArg()).thenReturn("");

        assertThrows(RuntimeException.class, () -> aspect.aroundRoot(joinPoint, traceRoot));

        verify(traceRecordService).startRun(any());
        verify(traceRecordService).finishRun(any(), eq("ERROR"), any(), any(), anyLong());
        assertNull(RagTraceContext.getTraceId());
    }

    @Test
    void testPopNodeOnEmptyStackIsSafe() {
        RagTraceContext.popNode();
        assertNull(RagTraceContext.currentNodeId());
        assertEquals(0, RagTraceContext.depth());
    }
}
