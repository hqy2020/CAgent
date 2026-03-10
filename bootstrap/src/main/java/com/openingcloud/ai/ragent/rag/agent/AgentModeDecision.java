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

package com.openingcloud.ai.ragent.rag.agent;

/**
 * Agent 模式决策结果
 */
public record AgentModeDecision(
        boolean enabled,
        String reason,
        Double confidence) {

    public static AgentModeDecision disabled(String reason) {
        return new AgentModeDecision(false, reason, null);
    }

    public static AgentModeDecision enabled(String reason, Double confidence) {
        return new AgentModeDecision(true, reason, confidence);
    }
}

