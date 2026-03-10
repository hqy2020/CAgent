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

import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModeDeciderTests {

    @Test
    void shouldEnableWhenMultipleSubQuestions() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        List<SubQuestionIntent> subIntents = List.of(
                new SubQuestionIntent("q1", List.of()),
                new SubQuestionIntent("q2", List.of())
        );
        AgentModeDecision decision = decider.decide("帮我整理并总结", subIntents, RetrievalContext.builder().intentChunks(Map.of()).build());
        assertTrue(decision.enabled());
    }

    @Test
    void shouldEnableWhenMixedKbAndMcp() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        IntentNode kbNode = IntentNode.builder().id("kb").kind(IntentKind.KB).build();
        IntentNode mcpNode = IntentNode.builder().id("mcp").kind(IntentKind.MCP).build();
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("q1", List.of(
                NodeScore.builder().node(kbNode).score(0.8).build(),
                NodeScore.builder().node(mcpNode).score(0.9).build()
        )));
        AgentModeDecision decision = decider.decide("帮我完成任务", subIntents, RetrievalContext.builder().intentChunks(Map.of()).build());
        assertTrue(decision.enabled());
    }

    @Test
    void shouldDisableWhenAgentOff() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(false, 0.55D));
        AgentModeDecision decision = decider.decide("你好", List.of(new SubQuestionIntent("你好", List.of())), RetrievalContext.builder().intentChunks(Map.of()).build());
        assertFalse(decision.enabled());
    }

    @Test
    void shouldDisableWhenDateTimeLookupQuestion() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        IntentNode mcpNode = IntentNode.builder()
                .id("obs-create-daily")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_create")
                .build();
        AgentModeDecision decision = decider.decide(
                "今天几号",
                List.of(new SubQuestionIntent("今天几号", List.of(NodeScore.builder().node(mcpNode).score(0.93D).build()))),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertFalse(decision.enabled());
    }

    @Test
    void shouldDisableWhenWebSearchLookupQuestion() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        IntentNode mcpNode = IntentNode.builder()
                .id("obs-create-daily")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_create")
                .build();
        AgentModeDecision decision = decider.decide(
                "帮我联网搜索一下今天的 AI 新闻",
                List.of(new SubQuestionIntent("帮我联网搜索一下今天的 AI 新闻", List.of(NodeScore.builder().node(mcpNode).score(0.93D).build()))),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertFalse(decision.enabled());
    }

    @Test
    void shouldDisableReadOnlyMcpQuery() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        IntentNode mcpNode = IntentNode.builder()
                .id("web-news-query")
                .kind(IntentKind.MCP)
                .mcpToolId("web_news_search")
                .build();
        AgentModeDecision decision = decider.decide(
                "帮我联网搜索今天的 AI 新闻",
                List.of(new SubQuestionIntent("帮我联网搜索今天的 AI 新闻", List.of(NodeScore.builder().node(mcpNode).score(0.93D).build()))),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertFalse(decision.enabled());
    }

    @Test
    void shouldEnableWhenQuestionContainsMutationVerb() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        IntentNode mcpNode = IntentNode.builder()
                .id("obs-create-daily")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_create")
                .build();
        AgentModeDecision decision = decider.decide(
                "帮我创建今天的日记",
                List.of(new SubQuestionIntent("帮我创建今天的日记", List.of(NodeScore.builder().node(mcpNode).score(0.93D).build()))),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertTrue(decision.enabled());
    }

    @Test
    void shouldEnableWhenQuestionLooksLikeObsidianWriteEvenWithoutIntent() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        AgentModeDecision decision = decider.decide(
                "帮我创建一篇今天的笔记，记录回归结果",
                List.of(),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertTrue(decision.enabled());
    }

    @Test
    void shouldEnableWhenQuestionUsesNaturalLanguageDailyAppend() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        AgentModeDecision decision = decider.decide(
                "帮我加一条灵感到我的今日日记里，多去用感官感受",
                List.of(),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertTrue(decision.enabled());
    }

    @Test
    void shouldEnableWhenKbRequestHasLowConfidence() {
        AgentModeDecider decider = new AgentModeDecider(buildConfig(true, 0.55D));
        IntentNode kbNode = IntentNode.builder()
                .id("kb-qa-bagu-java")
                .kind(IntentKind.KB)
                .build();
        AgentModeDecision decision = decider.decide(
                "HashMap 的底层原理是什么？",
                List.of(new SubQuestionIntent("HashMap 的底层原理是什么？", List.of(NodeScore.builder().node(kbNode).score(0.88D).build()))),
                RetrievalContext.builder().intentChunks(Map.of()).build()
        );
        assertTrue(decision.enabled());
    }

    private RAGConfigProperties buildConfig(boolean enabled, double threshold) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setAgentEnabled(enabled);
        properties.setAgentLowConfidenceThreshold(threshold);
        return properties;
    }
}
