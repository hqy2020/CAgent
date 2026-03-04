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

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.core.prompt.DefaultContextFormatter;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultContextFormatterTests {

    @Mock
    private MCPService mcpService;

    private DefaultContextFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DefaultContextFormatter(mcpService);
    }

    @Test
    void shouldKeepErrorResponseInMcpContext() {
        IntentNode node = IntentNode.builder()
                .id("obs-update")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .build();
        List<NodeScore> intents = List.of(NodeScore.builder().node(node).score(0.91).build());
        List<MCPResponse> responses = List.of(
                MCPResponse.error("obsidian_update", "DATE_CONFLICT", "冲突")
        );
        when(mcpService.mergeResponsesToText(anyList())).thenReturn("工具调用失败: DATE_CONFLICT");

        String context = formatter.formatMcpContext(responses, intents);

        assertTrue(context.contains("#### 动态数据片段"));
        assertTrue(context.contains("工具调用失败"));
    }

    @Test
    void shouldMergeSuccessAndErrorForSameToolWithoutFiltering() {
        IntentNode node = IntentNode.builder()
                .id("obs-update")
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .build();
        List<NodeScore> intents = List.of(NodeScore.builder().node(node).score(0.95).build());
        List<MCPResponse> responses = List.of(
                MCPResponse.success("obsidian_update", "ok"),
                MCPResponse.error("obsidian_update", "WRITE_VERIFY_FAILED", "校验失败")
        );
        when(mcpService.mergeResponsesToText(anyList())).thenAnswer(invocation -> {
            List<?> input = invocation.getArgument(0);
            return "size=" + input.size();
        });

        String context = formatter.formatMcpContext(responses, intents);

        assertTrue(context.contains("size=2"));
        verify(mcpService).mergeResponsesToText(argThat(each -> each.size() == 2));
    }
}

