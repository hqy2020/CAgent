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

package com.openingcloud.ai.ragent.rag.core.mcp.governance;

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 指标记录器。
 */
@Component
public class MCPMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> circuitOpenGauges = new ConcurrentHashMap<>();

    public MCPMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordCall(MCPRequest request, MCPResponse response) {
        String toolId = request == null ? "unknown" : request.getToolId();
        String source = request == null || request.getRequestSource() == null ? "DIRECT" : request.getRequestSource().name();
        String success = String.valueOf(response != null && response.isSuccess());
        String standardErrorCode = response == null
                ? "SYSTEM_ERROR"
                : (response.isSuccess() ? "NONE" : safe(response.getStandardErrorCode(), "SYSTEM_ERROR"));
        String fallback = String.valueOf(response != null && response.isFallbackUsed());
        Tags tags = Tags.of(
                "tool", safe(toolId, "unknown"),
                "source", safe(source, "DIRECT"),
                "success", success,
                "standard_error_code", standardErrorCode,
                "fallback", fallback
        );
        Counter.builder("ragent_mcp_tool_calls_total")
                .tags(tags)
                .register(meterRegistry)
                .increment();
        if (response != null) {
            Timer.builder("ragent_mcp_tool_call_duration")
                    .tags("tool", safe(toolId, "unknown"), "source", safe(source, "DIRECT"))
                    .register(meterRegistry)
                    .record(Math.max(0L, response.getCostMs()), TimeUnit.MILLISECONDS);
        }
    }

    public void recordRetry(String toolId, String source) {
        Counter.builder("ragent_mcp_tool_retries_total")
                .tags("tool", safe(toolId, "unknown"), "source", safe(source, "DIRECT"))
                .register(meterRegistry)
                .increment();
    }

    public void recordCircuitTransition(String toolId, String state) {
        Counter.builder("ragent_mcp_tool_circuit_transitions_total")
                .tags("tool", safe(toolId, "unknown"), "state", safe(state, "UNKNOWN"))
                .register(meterRegistry)
                .increment();
        gauge(toolId).set("OPEN".equalsIgnoreCase(state) ? 1 : 0);
    }

    public void syncCircuitState(String toolId, String state) {
        gauge(toolId).set("OPEN".equalsIgnoreCase(state) ? 1 : 0);
    }

    private AtomicInteger gauge(String toolId) {
        return circuitOpenGauges.computeIfAbsent(safe(toolId, "unknown"), key -> {
            AtomicInteger gauge = new AtomicInteger();
            meterRegistry.gauge("ragent_mcp_tool_circuit_open", Tags.of("tool", key), gauge);
            return gauge;
        });
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
