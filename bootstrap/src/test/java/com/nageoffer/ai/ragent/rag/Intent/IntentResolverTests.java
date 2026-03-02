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

package com.nageoffer.ai.ragent.rag.intent;

import com.nageoffer.ai.ragent.rag.core.intent.IntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentResolverTests {

    @Mock
    private IntentClassifier intentClassifier;
    private final Executor syncExecutor = Runnable::run;
    private IntentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new IntentResolver(intentClassifier, syncExecutor);
    }

    @Test
    void testMultiSubQuestionParallelClassification() {
        IntentNode kbNode = IntentNode.builder().id("kb-1").name("KB1").kind(IntentKind.KB).build();
        NodeScore highScore = NodeScore.builder().node(kbNode).score(0.9).build();

        when(intentClassifier.classifyTargets(anyString())).thenReturn(List.of(highScore));

        RewriteResult rewrite = new RewriteResult("rewritten", List.of("sub1", "sub2"));
        List<SubQuestionIntent> result = resolver.resolve(rewrite);

        assertEquals(2, result.size());
        assertEquals("sub1", result.get(0).subQuestion());
        assertEquals("sub2", result.get(1).subQuestion());
        assertFalse(result.get(0).nodeScores().isEmpty());
    }

    @Test
    void testLowScoreIntentFiltered() {
        IntentNode node = IntentNode.builder().id("kb-1").name("KB1").kind(IntentKind.KB).build();
        NodeScore lowScore = NodeScore.builder().node(node).score(0.01).build();

        when(intentClassifier.classifyTargets(anyString())).thenReturn(List.of(lowScore));

        RewriteResult rewrite = new RewriteResult("question", null);
        List<SubQuestionIntent> result = resolver.resolve(rewrite);

        assertEquals(1, result.size());
        assertTrue(result.get(0).nodeScores().isEmpty());
    }

    @Test
    void testMergeIntentGroupSeparatesMcpAndKb() {
        IntentNode kbNode = IntentNode.builder().id("kb-1").name("KB").kind(IntentKind.KB)
                .collectionName("col1").build();
        IntentNode mcpNode = IntentNode.builder().id("mcp-1").name("MCP").kind(IntentKind.MCP)
                .mcpToolId("tool-1").build();

        NodeScore kbScore = NodeScore.builder().node(kbNode).score(0.9).build();
        NodeScore mcpScore = NodeScore.builder().node(mcpNode).score(0.85).build();

        List<SubQuestionIntent> subIntents = List.of(
                new SubQuestionIntent("question", List.of(kbScore, mcpScore))
        );

        IntentGroup group = resolver.mergeIntentGroup(subIntents);

        assertEquals(1, group.kbIntents().size());
        assertEquals(1, group.mcpIntents().size());
        assertEquals("kb-1", group.kbIntents().get(0).getNode().getId());
        assertEquals("mcp-1", group.mcpIntents().get(0).getNode().getId());
    }

    @Test
    void testIsSystemOnlyWithSingleSystemIntent() {
        IntentNode sysNode = IntentNode.builder().id("sys-1").name("SYS").kind(IntentKind.SYSTEM).build();
        NodeScore sysScore = NodeScore.builder().node(sysNode).score(0.95).build();

        assertTrue(resolver.isSystemOnly(List.of(sysScore)));
        assertFalse(resolver.isSystemOnly(List.of()));

        IntentNode kbNode = IntentNode.builder().id("kb-1").name("KB").kind(IntentKind.KB).build();
        NodeScore kbScore = NodeScore.builder().node(kbNode).score(0.9).build();
        assertFalse(resolver.isSystemOnly(List.of(sysScore, kbScore)));
    }
}
