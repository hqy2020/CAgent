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

package com.nageoffer.ai.ragent.ingestion.service;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.service.impl.IntentTreeServiceImpl;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentTreeServiceImplTests {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;
    @Mock
    private IntentNodeMapper intentNodeMapper;

    private IntentTreeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IntentTreeServiceImpl(knowledgeBaseMapper, intentTreeCacheManager);
        ReflectionTestUtils.setField(service, "baseMapper", intentNodeMapper);
    }

    @Test
    void createNodeShouldThrowClientExceptionWhenKbNotExists() {
        IntentNodeCreateRequest request = IntentNodeCreateRequest.builder()
                .intentCode("kb-non-exists")
                .name("不存在知识库测试")
                .level(IntentLevel.DOMAIN.getCode())
                .kind(IntentKind.KB.getCode())
                .kbId("999999")
                .build();

        when(intentNodeMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeBaseMapper.selectById(999999L)).thenReturn(null);

        ClientException ex = assertThrows(ClientException.class, () -> service.createNode(request));
        assertEquals("知识库不存在", ex.getMessage());
        verify(intentNodeMapper, never()).insert(org.mockito.ArgumentMatchers.<com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO>any());
    }

    @Test
    void syncManagedFieldsShouldUpdateOnlyCodeManagedFields() {
        IntentNodeDO existing = IntentNodeDO.builder()
                .name("旧名称")
                .description("旧描述")
                .examples("[\"旧示例\"]")
                .mcpToolId("old_tool")
                .parentCode("old-parent")
                .level(IntentLevel.CATEGORY.getCode())
                .kind(IntentKind.KB.getCode())
                .promptTemplate("old-template")
                .promptSnippet("old-snippet")
                .paramPromptTemplate("old-param")
                .enabled(0)
                .sortOrder(99)
                .build();
        IntentNode factoryNode = IntentNode.builder()
                .id("obs-query-read")
                .name("读取笔记")
                .description("读取指定笔记全文")
                .examples(java.util.List.of("打开 README"))
                .mcpToolId("obsidian_read")
                .parentId("obs-query")
                .level(IntentLevel.TOPIC)
                .kind(IntentKind.MCP)
                .promptTemplate("new-template")
                .promptSnippet("new-snippet")
                .paramPromptTemplate("new-param")
                .build();

        boolean changed = ReflectionTestUtils.invokeMethod(service, "syncManagedFields", existing, factoryNode);

        assertEquals(true, changed);
        assertEquals("读取笔记", existing.getName());
        assertEquals("读取指定笔记全文", existing.getDescription());
        assertEquals("[\"打开 README\"]", existing.getExamples());
        assertEquals("obsidian_read", existing.getMcpToolId());
        assertEquals("obs-query", existing.getParentCode());
        assertEquals(IntentLevel.TOPIC.getCode(), existing.getLevel());
        assertEquals(IntentKind.MCP.getCode(), existing.getKind());
        assertEquals("new-template", existing.getPromptTemplate());
        assertEquals("new-snippet", existing.getPromptSnippet());
        assertEquals("new-param", existing.getParamPromptTemplate());
        assertEquals(0, existing.getEnabled());
        assertEquals(99, existing.getSortOrder());
    }
}
