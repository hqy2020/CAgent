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

package com.openingcloud.ai.ragent.ingestion.service;

import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.ingestion.service.impl.IntentTreeServiceImpl;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.openingcloud.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.openingcloud.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.openingcloud.ai.ragent.rag.enums.IntentKind;
import com.openingcloud.ai.ragent.rag.enums.IntentLevel;
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
        verify(intentNodeMapper, never()).insert(org.mockito.ArgumentMatchers.<com.openingcloud.ai.ragent.rag.dao.entity.IntentNodeDO>any());
    }
}
