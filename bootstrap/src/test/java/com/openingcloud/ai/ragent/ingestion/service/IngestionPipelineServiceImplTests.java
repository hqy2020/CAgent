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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.openingcloud.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.openingcloud.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.openingcloud.ai.ragent.ingestion.service.impl.IngestionPipelineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionPipelineServiceImplTests {

    @Mock
    private IngestionPipelineMapper pipelineMapper;
    @Mock
    private IngestionPipelineNodeMapper nodeMapper;

    private IngestionPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IngestionPipelineServiceImpl(pipelineMapper, nodeMapper, new ObjectMapper());
    }

    @Test
    void testDeleteShouldCallLogicalDeleteAndDeleteNodes() {
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder().id(123L).build();
        when(pipelineMapper.selectById("123")).thenReturn(pipeline);
        when(pipelineMapper.deleteById(123L)).thenReturn(1);

        service.delete("123");

        verify(pipelineMapper).deleteById(123L);
        verify(nodeMapper).delete(any());
        verify(pipelineMapper, never()).updateById(any(IngestionPipelineDO.class));
    }

    @Test
    void testDeleteShouldThrowWhenPipelineNotFound() {
        when(pipelineMapper.selectById("123")).thenReturn(null);

        assertThrows(ClientException.class, () -> service.delete("123"));

        verify(nodeMapper, never()).delete(any());
    }

    @Test
    void testDeleteShouldThrowWhenLogicalDeleteFailed() {
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder().id(123L).build();
        when(pipelineMapper.selectById("123")).thenReturn(pipeline);
        when(pipelineMapper.deleteById(123L)).thenReturn(0);

        assertThrows(ClientException.class, () -> service.delete("123"));

        verify(nodeMapper, never()).delete(any());
    }
}
