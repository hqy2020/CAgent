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

import com.openingcloud.ai.ragent.framework.trace.RagTraceNode;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent 模式判定器
 */
@Component
public class AgentModeDecider {

    private final RAGConfigProperties ragConfigProperties;

    @Autowired
    public AgentModeDecider(RAGConfigProperties ragConfigProperties) {
        this.ragConfigProperties = ragConfigProperties;
    }

    @RagTraceNode(name = "agent-decide", type = "AGENT_DECIDE")
    public AgentModeDecision decide(String question,
                                    List<SubQuestionIntent> subIntents,
                                    RetrievalContext firstRoundContext) {
        if (!Boolean.TRUE.equals(ragConfigProperties.getAgentEnabled())) {
            return AgentModeDecision.disabled("agent disabled");
        }

        // 所有问题强制走 ReAct Agent 模式
        return AgentModeDecision.enabled("default enabled", resolveBestScore(firstRoundContext));
    }

    private double resolveBestScore(RetrievalContext context) {
        if (context == null || context.getIntentChunks() == null || context.getIntentChunks().isEmpty()) {
            return 0.0D;
        }
        double best = 0D;
        for (Map.Entry<String, List<RetrievedChunk>> entry : context.getIntentChunks().entrySet()) {
            List<RetrievedChunk> chunks = entry.getValue();
            if (chunks == null) {
                continue;
            }
            for (RetrievedChunk chunk : chunks) {
                if (chunk != null && chunk.getScore() != null) {
                    best = Math.max(best, chunk.getScore());
                }
            }
        }
        return best;
    }
}
