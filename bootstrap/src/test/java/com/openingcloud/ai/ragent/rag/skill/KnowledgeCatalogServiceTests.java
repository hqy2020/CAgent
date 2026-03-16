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

package com.openingcloud.ai.ragent.rag.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.ingestion.service.IntentTreeService;
import com.openingcloud.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeCatalogServiceTests {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private IntentTreeService intentTreeService;

    private KnowledgeCatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new KnowledgeCatalogService(
                knowledgeBaseMapper, knowledgeDocumentMapper, intentTreeService
        );
    }

    @Test
    void shouldReturnEmptyMessageWhenTreeIsEmpty() {
        when(intentTreeService.getFullTree()).thenReturn(Collections.emptyList());

        String result = catalogService.buildCatalogPrompt();

        assertEquals("当前没有可用的知识库。", result);
    }

    @Test
    void shouldReturnEmptyMessageWhenNoKbNodes() {
        // MCP 类型节点（kind=2），不是 KB
        IntentNodeTreeVO mcpNode = IntentNodeTreeVO.builder()
                .id("1")
                .kind(2)
                .enabled(1)
                .collectionName("test_col")
                .build();
        when(intentTreeService.getFullTree()).thenReturn(List.of(mcpNode));

        String result = catalogService.buildCatalogPrompt();

        assertEquals("当前没有可用的知识库。", result);
    }

    @Test
    void shouldBuildCatalogForKbNodes() {
        IntentNodeTreeVO kbNode = IntentNodeTreeVO.builder()
                .id("1")
                .kind(0)
                .enabled(1)
                .collectionName("spring_interview")
                .description("Spring 面试题集")
                .build();
        when(intentTreeService.getFullTree()).thenReturn(List.of(kbNode));

        KnowledgeBaseDO kb = new KnowledgeBaseDO();
        kb.setId(100L);
        kb.setName("Spring面试题");
        kb.setCollectionName("spring_interview");
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(kb);
        when(knowledgeDocumentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(85L);

        String result = catalogService.buildCatalogPrompt();

        assertTrue(result.contains("## 可用知识库"));
        assertTrue(result.contains("100"));
        assertTrue(result.contains("Spring面试题"));
        assertTrue(result.contains("Spring 面试题集"));
        assertTrue(result.contains("85"));
    }

    @Test
    void shouldSkipDisabledNodes() {
        IntentNodeTreeVO disabledNode = IntentNodeTreeVO.builder()
                .id("1")
                .kind(0)
                .enabled(0) // disabled
                .collectionName("test_col")
                .build();
        when(intentTreeService.getFullTree()).thenReturn(List.of(disabledNode));

        String result = catalogService.buildCatalogPrompt();

        assertEquals("当前没有可用的知识库。", result);
    }

    @Test
    void shouldHandleNestedTreeStructure() {
        IntentNodeTreeVO childKb = IntentNodeTreeVO.builder()
                .id("2")
                .kind(0)
                .enabled(1)
                .collectionName("java_basics")
                .description("Java 基础知识")
                .build();
        IntentNodeTreeVO parent = IntentNodeTreeVO.builder()
                .id("1")
                .kind(null) // null kind treated as KB
                .enabled(0)
                .children(List.of(childKb))
                .build();
        when(intentTreeService.getFullTree()).thenReturn(List.of(parent));

        KnowledgeBaseDO kb = new KnowledgeBaseDO();
        kb.setId(200L);
        kb.setName("Java基础");
        kb.setCollectionName("java_basics");
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(kb);
        when(knowledgeDocumentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(120L);

        String result = catalogService.buildCatalogPrompt();

        assertTrue(result.contains("200"));
        assertTrue(result.contains("Java基础"));
        assertTrue(result.contains("120"));
    }
}
