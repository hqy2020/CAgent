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

package com.openingcloud.ai.ragent.rag.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.DefaultMCPToolRegistry;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPServiceOrchestrator;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPAuditRecorder;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPErrorClassifier;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPFallbackCache;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPFallbackResolver;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPMetricsRecorder;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPPayloadSanitizer;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPToolHealthStore;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPToolSecurityGuard;
import com.openingcloud.ai.ragent.rag.dao.mapper.MCPToolCallAuditMapper;
import com.openingcloud.ai.ragent.rag.service.RagTraceRecordService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPServiceOrchestratorGovernanceTests {

    @Test
    void shouldRetryTransientFailureUntilSuccess() {
        RagMcpExecutionProperties properties = createProperties();
        AtomicInteger executionCount = new AtomicInteger();
        MCPToolExecutor executor = toolExecutor(
                MCPTool.builder()
                        .toolId("web_news_search")
                        .name("Web Search")
                        .operationType(MCPTool.OperationType.READ)
                        .requireUserId(false)
                        .maxRetries(2)
                        .build(),
                request -> executionCount.incrementAndGet() == 1
                        ? MCPResponse.error("web_news_search", "503", "upstream unavailable",
                        MCPResponse.ErrorType.TRANSIENT, true)
                        : MCPResponse.success("web_news_search", "fresh-result")
        );

        MCPServiceOrchestrator orchestrator = createOrchestrator(
                List.of(executor),
                properties,
                new MCPToolHealthStore(properties),
                new InMemoryFallbackCache(new ObjectMapper(), properties)
        );

        MCPResponse response = orchestrator.execute(MCPRequest.builder()
                .toolId("web_news_search")
                .build());

        assertTrue(response.isSuccess());
        assertEquals("fresh-result", response.getTextResult());
        assertEquals(2, executionCount.get());
    }

    @Test
    void shouldUseCachedFallbackAfterRetryableFailures() {
        RagMcpExecutionProperties properties = createProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        InMemoryFallbackCache fallbackCache = new InMemoryFallbackCache(objectMapper, properties);
        MCPTool tool = MCPTool.builder()
                .toolId("web_news_search")
                .name("Web Search")
                .operationType(MCPTool.OperationType.READ)
                .requireUserId(false)
                .cacheableFallback(true)
                .maxRetries(1)
                .build();

        MCPServiceOrchestrator warmupOrchestrator = createOrchestrator(
                List.of(toolExecutor(tool, request -> MCPResponse.success("web_news_search", "cached-result"))),
                properties,
                new MCPToolHealthStore(properties),
                fallbackCache
        );
        MCPRequest request = MCPRequest.builder()
                .toolId("web_news_search")
                .userQuestion("latest ai news")
                .build();
        MCPResponse warmup = warmupOrchestrator.execute(request);
        assertTrue(warmup.isSuccess());

        AtomicInteger failingExecutionCount = new AtomicInteger();
        MCPServiceOrchestrator failingOrchestrator = createOrchestrator(
                List.of(toolExecutor(tool, ignored -> {
                    failingExecutionCount.incrementAndGet();
                    return MCPResponse.error("web_news_search", "503", "bridge unavailable",
                            MCPResponse.ErrorType.TRANSIENT, true);
                })),
                properties,
                new MCPToolHealthStore(properties),
                fallbackCache
        );

        MCPResponse response = failingOrchestrator.execute(MCPRequest.builder()
                .toolId("web_news_search")
                .userQuestion("latest ai news")
                .build());

        assertTrue(response.isSuccess());
        assertTrue(response.isFallbackUsed());
        assertTrue(response.getTextResult().startsWith("[fallback]"));
        assertEquals(2, failingExecutionCount.get());
    }

    @Test
    void shouldShortCircuitOpenCircuitAndReturnFallbackWithoutExecutingTool() {
        RagMcpExecutionProperties properties = createProperties();
        properties.getCircuitBreaker().setFailureThreshold(1);
        properties.getCircuitBreaker().setOpenDurationMs(60_000L);
        ObjectMapper objectMapper = new ObjectMapper();
        InMemoryFallbackCache fallbackCache = new InMemoryFallbackCache(objectMapper, properties);
        MCPToolHealthStore healthStore = new MCPToolHealthStore(properties);

        MCPTool tool = MCPTool.builder()
                .toolId("web_news_search")
                .name("Web Search")
                .operationType(MCPTool.OperationType.READ)
                .requireUserId(false)
                .cacheableFallback(true)
                .build();
        MCPRequest request = MCPRequest.builder()
                .toolId("web_news_search")
                .userQuestion("latest ai news")
                .build();
        fallbackCache.cacheSuccess(request, tool, MCPResponse.success("web_news_search", "cached-result"));
        healthStore.markFailure("web_news_search");

        AtomicInteger executionCount = new AtomicInteger();
        MCPServiceOrchestrator orchestrator = createOrchestrator(
                List.of(toolExecutor(tool, ignored -> {
                    executionCount.incrementAndGet();
                    return MCPResponse.success("web_news_search", "should-not-run");
                })),
                properties,
                healthStore,
                fallbackCache
        );

        MCPResponse response = orchestrator.execute(MCPRequest.builder()
                .toolId("web_news_search")
                .userQuestion("latest ai news")
                .build());

        assertTrue(response.isSuccess());
        assertTrue(response.isFallbackUsed());
        assertEquals(0, executionCount.get());
    }

    @Test
    void shouldIgnoreAuditPersistenceFailureAndStillReturnToolResponse() {
        RagMcpExecutionProperties properties = createProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        MCPPayloadSanitizer payloadSanitizer = new MCPPayloadSanitizer(properties);
        MCPTool tool = MCPTool.builder()
                .toolId("web_search")
                .name("Web Search")
                .operationType(MCPTool.OperationType.READ)
                .requireUserId(false)
                .build();

        MCPToolCallAuditMapper failingAuditMapper = (MCPToolCallAuditMapper) Proxy.newProxyInstance(
                MCPToolCallAuditMapper.class.getClassLoader(),
                new Class[]{MCPToolCallAuditMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        throw new RuntimeException("missing audit table");
                    }
                    return null;
                }
        );

        MCPServiceOrchestrator orchestrator = createOrchestrator(
                List.of(toolExecutor(tool, ignored -> MCPResponse.success("web_search", "1688 is part of Alibaba"))),
                properties,
                new MCPToolHealthStore(properties),
                new InMemoryFallbackCache(objectMapper, properties),
                new MCPAuditRecorder(objectMapper, payloadSanitizer, failingAuditMapper, noopTraceRecordService())
        );

        MCPResponse response = orchestrator.execute(MCPRequest.builder()
                .toolId("web_search")
                .userQuestion("1688-JAVA-工厂技术 做什么的")
                .build());

        assertTrue(response.isSuccess());
        assertEquals("1688 is part of Alibaba", response.getTextResult());
    }

    private MCPServiceOrchestrator createOrchestrator(List<MCPToolExecutor> executors,
                                                      RagMcpExecutionProperties properties,
                                                      MCPToolHealthStore healthStore,
                                                      MCPFallbackCache fallbackCache) {
        return createOrchestrator(executors, properties, healthStore, fallbackCache, createAuditRecorder(properties));
    }

    private MCPServiceOrchestrator createOrchestrator(List<MCPToolExecutor> executors,
                                                      RagMcpExecutionProperties properties,
                                                      MCPToolHealthStore healthStore,
                                                      MCPFallbackCache fallbackCache,
                                                      MCPAuditRecorder auditRecorder) {
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(executors);
        registry.init();
        return new MCPServiceOrchestrator(
                registry,
                Runnable::run,
                Runnable::run,
                properties,
                healthStore,
                new MCPErrorClassifier(),
                new MCPMetricsRecorder(new SimpleMeterRegistry()),
                new MCPFallbackResolver(fallbackCache),
                new MCPToolSecurityGuard(),
                auditRecorder
        );
    }

    private MCPAuditRecorder createAuditRecorder(RagMcpExecutionProperties properties) {
        ObjectMapper objectMapper = new ObjectMapper();
        MCPPayloadSanitizer payloadSanitizer = new MCPPayloadSanitizer(properties);
        return new MCPAuditRecorder(objectMapper, payloadSanitizer,
                (MCPToolCallAuditMapper) null, (RagTraceRecordService) null);
    }

    private MCPToolExecutor toolExecutor(MCPTool tool, java.util.function.Function<MCPRequest, MCPResponse> handler) {
        return new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return tool;
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return handler.apply(request);
            }
        };
    }

    private RagMcpExecutionProperties createProperties() {
        RagMcpExecutionProperties properties = new RagMcpExecutionProperties();
        properties.getRetry().setInitialDelayMs(1L);
        properties.getRetry().setMaxDelayMs(2L);
        properties.getCircuitBreaker().setFailureThreshold(3);
        properties.getCircuitBreaker().setOpenDurationMs(20L);
        properties.getCircuitBreaker().setMaxOpenDurationMs(100L);
        return properties;
    }

    private RagTraceRecordService noopTraceRecordService() {
        return new RagTraceRecordService() {
            @Override
            public void startRun(com.openingcloud.ai.ragent.rag.dao.entity.RagTraceRunDO run) {
            }

            @Override
            public void finishRun(String traceId, String status, String errorMessage, java.util.Date endTime, long durationMs) {
            }

            @Override
            public void startNode(com.openingcloud.ai.ragent.rag.dao.entity.RagTraceNodeDO node) {
            }

            @Override
            public void finishNode(String traceId, String nodeId, String status, String errorMessage, java.util.Date endTime,
                                   long durationMs) {
            }

            @Override
            public void updateNodeExtraData(String traceId, String nodeId, java.util.Map<String, Object> extraData) {
            }
        };
    }

    private static final class InMemoryFallbackCache extends MCPFallbackCache {

        private final ObjectMapper objectMapper;
        private final ConcurrentHashMap<String, MCPResponse> cache = new ConcurrentHashMap<>();

        private InMemoryFallbackCache(ObjectMapper objectMapper, RagMcpExecutionProperties properties) {
            super((org.springframework.data.redis.core.StringRedisTemplate) null, objectMapper, properties);
            this.objectMapper = objectMapper;
        }

        @Override
        public void cacheSuccess(MCPRequest request, MCPTool tool, MCPResponse response) {
            if (request == null || tool == null || response == null || !tool.isCacheableFallback()
                    || !response.isSuccess() || response.isFallbackUsed()) {
                return;
            }
            cache.put(cacheKey(request), copy(response));
        }

        @Override
        public MCPResponse load(MCPRequest request, MCPTool tool) {
            MCPResponse cached = cache.get(cacheKey(request));
            return cached == null ? null : copy(cached);
        }

        private String cacheKey(MCPRequest request) {
            return request.getToolId() + "|" + request.getUserQuestion() + "|" + request.getParameters();
        }

        private MCPResponse copy(MCPResponse response) {
            return objectMapper.convertValue(response, MCPResponse.class);
        }
    }
}
