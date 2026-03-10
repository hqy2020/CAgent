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

import com.openingcloud.ai.ragent.rag.config.RagMcpExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具健康状态存储。
 */
@Component
@RequiredArgsConstructor
public class MCPToolHealthStore {

    private final RagMcpExecutionProperties properties;

    private final Map<String, ToolHealth> healthByToolId = new ConcurrentHashMap<>();

    public boolean allowCall(String toolId) {
        if (toolId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        final boolean[] allowed = {false};
        healthByToolId.compute(toolId, (key, value) -> {
            ToolHealth health = value == null ? new ToolHealth() : value;
            if (health.state == State.OPEN) {
                if (health.openUntil > now) {
                    return health;
                }
                health.state = State.HALF_OPEN;
                health.halfOpenInFlight = true;
                allowed[0] = true;
                return health;
            }
            if (health.state == State.HALF_OPEN) {
                if (health.halfOpenInFlight) {
                    return health;
                }
                health.halfOpenInFlight = true;
                allowed[0] = true;
                return health;
            }
            allowed[0] = true;
            return health;
        });
        return allowed[0];
    }

    public void markSuccess(String toolId) {
        if (toolId == null) {
            return;
        }
        healthByToolId.compute(toolId, (key, value) -> {
            ToolHealth health = value == null ? new ToolHealth() : value;
            health.state = State.CLOSED;
            health.consecutiveFailures = 0;
            health.openAttempts = 0;
            health.openUntil = 0L;
            health.halfOpenInFlight = false;
            return health;
        });
    }

    public void markNeutral(String toolId) {
        if (toolId == null) {
            return;
        }
        healthByToolId.computeIfPresent(toolId, (key, value) -> {
            value.halfOpenInFlight = false;
            if (value.state == State.OPEN && value.openUntil <= System.currentTimeMillis()) {
                value.state = State.CLOSED;
                value.openUntil = 0L;
                value.consecutiveFailures = 0;
            }
            return value;
        });
    }

    public void markFailure(String toolId) {
        if (toolId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthByToolId.compute(toolId, (key, value) -> {
            ToolHealth health = value == null ? new ToolHealth() : value;
            if (health.state == State.HALF_OPEN) {
                health.state = State.OPEN;
                health.openAttempts++;
                health.openUntil = now + computeBackoffDuration(health.openAttempts);
                health.consecutiveFailures = 0;
                health.halfOpenInFlight = false;
                return health;
            }
            health.consecutiveFailures++;
            if (health.consecutiveFailures >= properties.getCircuitBreaker().getFailureThreshold()) {
                health.state = State.OPEN;
                health.openAttempts = Math.max(1, health.openAttempts + 1);
                health.openUntil = now + computeBackoffDuration(health.openAttempts);
                health.consecutiveFailures = 0;
                health.halfOpenInFlight = false;
            }
            return health;
        });
    }

    public String currentState(String toolId) {
        if (toolId == null) {
            return State.CLOSED.name();
        }
        ToolHealth health = healthByToolId.computeIfPresent(toolId, (key, value) -> {
            if (value.state == State.OPEN && value.openUntil <= System.currentTimeMillis()) {
                value.state = State.CLOSED;
                value.openUntil = 0L;
                value.consecutiveFailures = 0;
                value.halfOpenInFlight = false;
            }
            return value;
        });
        if (health == null) {
            return State.CLOSED.name();
        }
        return health.state.name();
    }

    public boolean isOpen(String toolId) {
        if (toolId == null) {
            return false;
        }
        ToolHealth health = healthByToolId.get(toolId);
        return health != null && health.state == State.OPEN && health.openUntil > System.currentTimeMillis();
    }

    private long computeBackoffDuration(int openAttempts) {
        long baseDuration = properties.getCircuitBreaker().getOpenDurationMs();
        long maxDuration = properties.getCircuitBreaker().getMaxOpenDurationMs();
        int exponent = Math.min(Math.max(0, openAttempts - 1), 30);
        long duration = baseDuration * (1L << exponent);
        return Math.min(duration, maxDuration);
    }

    private static class ToolHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private int openAttempts;
        private State state = State.CLOSED;
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
