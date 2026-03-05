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

package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_KB_MIXED_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_CORE_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_DETAILED_MODE_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_LINK_MEDIA_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.PROGRESSIVE_PROMPT_MULTI_QUESTION_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.RAG_ENTERPRISE_PROMPT_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGPromptServiceTests {

    @Mock
    private PromptTemplateLoader promptTemplateLoader;

    @Mock
    private RAGConfigProperties ragConfigProperties;

    @InjectMocks
    private RAGPromptService ragPromptService;

    @BeforeEach
    void setUp() {
        lenient().when(ragConfigProperties.getPromptProgressiveEnabled()).thenReturn(true);
        lenient().when(ragConfigProperties.getPromptProgressiveCoreEnabled()).thenReturn(true);
        lenient().when(ragConfigProperties.getPromptProgressiveOptionalMultiQuestionEnabled()).thenReturn(true);
        lenient().when(ragConfigProperties.getPromptProgressiveOptionalLinkMediaEnabled()).thenReturn(true);
        lenient().when(ragConfigProperties.getPromptProgressiveOptionalDetailedModeEnabled()).thenReturn(true);
    }

    @Test
    void shouldAssembleCoreAndSceneForKbOnly() {
        when(promptTemplateLoader.load(PROGRESSIVE_PROMPT_CORE_PATH)).thenReturn("<core>");
        when(promptTemplateLoader.load(RAG_ENTERPRISE_PROMPT_PATH)).thenReturn("<kb-scene>");

        PromptContext context = PromptContext.builder()
                .question("年假怎么计算")
                .kbContext("文档内容")
                .build();

        List<ChatMessage> messages = ragPromptService.buildStructuredMessages(
                context,
                List.of(),
                "年假怎么计算",
                List.of("年假怎么计算")
        );

        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        assertTrue(messages.get(0).getContent().contains("<core>"));
        assertTrue(messages.get(0).getContent().contains("<kb-scene>"));
        verify(promptTemplateLoader, never()).load(PROGRESSIVE_PROMPT_MULTI_QUESTION_PATH);
        verify(promptTemplateLoader, never()).load(PROGRESSIVE_PROMPT_LINK_MEDIA_PATH);
        verify(promptTemplateLoader, never()).load(PROGRESSIVE_PROMPT_DETAILED_MODE_PATH);
    }

    @Test
    void shouldLoadOptionalRulesWhenComplexSignalsExist() {
        when(promptTemplateLoader.load(PROGRESSIVE_PROMPT_CORE_PATH)).thenReturn("<core>");
        when(promptTemplateLoader.load(MCP_KB_MIXED_PROMPT_PATH)).thenReturn("<mixed-scene>");
        when(promptTemplateLoader.load(PROGRESSIVE_PROMPT_MULTI_QUESTION_PATH)).thenReturn("<multi>");
        when(promptTemplateLoader.load(PROGRESSIVE_PROMPT_LINK_MEDIA_PATH)).thenReturn("<link-media>");
        when(promptTemplateLoader.load(PROGRESSIVE_PROMPT_DETAILED_MODE_PATH)).thenReturn("<detail>");

        PromptContext context = PromptContext.builder()
                .question("请详细展开说明")
                .kbContext("文档含链接 https://example.com")
                .mcpContext("动态数据")
                .build();

        List<ChatMessage> messages = ragPromptService.buildStructuredMessages(
                context,
                List.of(),
                "请详细展开说明",
                List.of("问题一", "问题二")
        );

        String systemPrompt = messages.get(0).getContent();
        assertTrue(systemPrompt.contains("<core>"));
        assertTrue(systemPrompt.contains("<mixed-scene>"));
        assertTrue(systemPrompt.contains("<multi>"));
        assertTrue(systemPrompt.contains("<link-media>"));
        assertTrue(systemPrompt.contains("<detail>"));
    }

    @Test
    void shouldWrapIntentCustomTemplateWithCoreRules() {
        when(promptTemplateLoader.load(PROGRESSIVE_PROMPT_CORE_PATH)).thenReturn("<core>");

        IntentNode node = IntentNode.builder()
                .id("kb-node-1")
                .kind(IntentKind.KB)
                .promptTemplate("<custom-template>")
                .build();
        NodeScore score = NodeScore.builder().node(node).score(0.95D).build();

        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("c1")
                .text("chunk")
                .build();

        PromptContext context = PromptContext.builder()
                .question("流程")
                .kbContext("文档")
                .kbIntents(List.of(score))
                .intentChunks(Map.of("kb-node-1", List.of(chunk)))
                .build();

        List<ChatMessage> messages = ragPromptService.buildStructuredMessages(
                context,
                List.of(),
                "流程",
                List.of("流程")
        );

        String systemPrompt = messages.get(0).getContent();
        assertTrue(systemPrompt.contains("<core>"));
        assertTrue(systemPrompt.contains("<custom-template>"));
        verify(promptTemplateLoader, never()).load(RAG_ENTERPRISE_PROMPT_PATH);
    }
}
