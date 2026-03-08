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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 工具执行治理配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.mcp.execution")
public class RagMcpExecutionProperties {

    /**
     * 默认单次调用超时时间（秒）。
     */
    private int defaultTimeoutSeconds = 15;

    /**
     * Fallback 缓存默认 TTL（秒）。
     */
    private int defaultFallbackCacheTtlSeconds = 300;

    /**
     * 审计与 trace 文本最大长度。
     */
    private int auditPayloadMaxChars = 2000;

    private Retry retry = new Retry();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Data
    public static class Retry {

        private long initialDelayMs = 1000L;

        private long maxDelayMs = 4000L;
    }

    @Data
    public static class CircuitBreaker {

        private int failureThreshold = 3;

        private long openDurationMs = 45000L;

        private long maxOpenDurationMs = 300000L;
    }
}
