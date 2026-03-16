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

package com.openingcloud.ai.ragent.rag.memory;

import cn.hutool.json.JSONUtil;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.rag.config.MemoryProperties;
import com.openingcloud.ai.ragent.rag.core.memory.MySQLConversationMemoryStore;
import com.openingcloud.ai.ragent.rag.core.memory.RedisConversationMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisConversationMemoryStoreTests {

    @Mock
    private MySQLConversationMemoryStore mysqlStore;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private MemoryProperties memoryProperties;
    @Mock
    private RList<String> redisList;

    private RedisConversationMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new RedisConversationMemoryStore(mysqlStore, redissonClient, memoryProperties);
        lenient().when(redissonClient.<String>getList(anyString())).thenReturn(redisList);
        lenient().when(memoryProperties.getHistoryKeepTurns()).thenReturn(5);
        lenient().when(memoryProperties.getTtlMinutes()).thenReturn(60);
    }

    @Test
    void shouldReturnCachedHistoryWhenRedisHit() {
        List<String> cachedJson = List.of(
                JSONUtil.toJsonStr(ChatMessage.user("hello")),
                JSONUtil.toJsonStr(ChatMessage.assistant("hi"))
        );
        when(redisList.isExists()).thenReturn(true);
        when(redisList.isEmpty()).thenReturn(false);
        when(redisList.readAll()).thenReturn(cachedJson);

        List<ChatMessage> result = store.loadHistory("conv-1", "user-1");

        assertEquals(2, result.size());
        assertEquals("hello", result.get(0).getContent());
        assertEquals(ChatMessage.Role.USER, result.get(0).getRole());
        assertEquals("hi", result.get(1).getContent());
        verify(mysqlStore, never()).loadHistory(anyString(), anyString());
    }

    @Test
    void shouldFallbackToMySQLWhenRedisMiss() {
        when(redisList.isExists()).thenReturn(false);
        List<ChatMessage> mysqlHistory = List.of(
                ChatMessage.user("question"),
                ChatMessage.assistant("answer")
        );
        when(mysqlStore.loadHistory("conv-2", "user-2")).thenReturn(mysqlHistory);

        List<ChatMessage> result = store.loadHistory("conv-2", "user-2");

        assertEquals(2, result.size());
        assertEquals("question", result.get(0).getContent());
        verify(mysqlStore).loadHistory("conv-2", "user-2");
    }

    @Test
    void shouldFallbackToMySQLWhenRedisThrows() {
        when(redisList.isExists()).thenThrow(new RuntimeException("Redis connection refused"));
        List<ChatMessage> mysqlHistory = List.of(ChatMessage.user("test"));
        when(mysqlStore.loadHistory("conv-3", "user-3")).thenReturn(mysqlHistory);

        List<ChatMessage> result = store.loadHistory("conv-3", "user-3");

        assertEquals(1, result.size());
        assertEquals("test", result.get(0).getContent());
    }

    @Test
    void shouldAppendToMySQLAndRedis() {
        ChatMessage msg = ChatMessage.user("new question");
        when(mysqlStore.append("conv-4", "user-4", msg)).thenReturn(100L);
        when(redisList.size()).thenReturn(2);

        Long messageId = store.append("conv-4", "user-4", msg);

        assertEquals(100L, messageId);
        verify(mysqlStore).append("conv-4", "user-4", msg);
        verify(redisList).add(JSONUtil.toJsonStr(msg));
    }

    @Test
    void shouldTrimRedisListWhenExceedsMaxSize() {
        ChatMessage msg = ChatMessage.user("overflow");
        when(mysqlStore.append("conv-5", "user-5", msg)).thenReturn(200L);
        when(redisList.size()).thenReturn(12); // maxSize = 5*2 = 10, excess = 2

        store.append("conv-5", "user-5", msg);

        verify(redisList, times(2)).remove(0);
    }

    @Test
    void shouldStillReturnMessageIdWhenRedisAppendFails() {
        ChatMessage msg = ChatMessage.user("test");
        when(mysqlStore.append("conv-6", "user-6", msg)).thenReturn(300L);
        when(redisList.add(anyString())).thenThrow(new RuntimeException("Redis write error"));

        Long messageId = store.append("conv-6", "user-6", msg);

        assertEquals(300L, messageId);
        verify(mysqlStore).append("conv-6", "user-6", msg);
    }

    @Test
    void shouldRefreshCacheFromMySQL() {
        List<ChatMessage> mysqlHistory = List.of(
                ChatMessage.user("q1"),
                ChatMessage.assistant("a1")
        );
        when(mysqlStore.loadHistory("conv-7", "user-7")).thenReturn(mysqlHistory);

        store.refreshCache("conv-7", "user-7");

        verify(mysqlStore).loadHistory("conv-7", "user-7");
        verify(redisList).delete();
        verify(redisList).addAll(anyList());
    }
}
