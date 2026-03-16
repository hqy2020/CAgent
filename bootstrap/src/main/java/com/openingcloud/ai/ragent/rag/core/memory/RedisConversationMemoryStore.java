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

package com.openingcloud.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.rag.config.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@ConditionalOnBean(RedissonClient.class)
public class RedisConversationMemoryStore implements ConversationMemoryStore {

    private static final String HISTORY_KEY_PREFIX = "ragent:memory:history:";

    private final MySQLConversationMemoryStore mysqlStore;
    private final RedissonClient redissonClient;
    private final MemoryProperties memoryProperties;

    public RedisConversationMemoryStore(MySQLConversationMemoryStore mysqlStore,
                                        RedissonClient redissonClient,
                                        MemoryProperties memoryProperties) {
        this.mysqlStore = mysqlStore;
        this.redissonClient = redissonClient;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        String key = buildHistoryKey(conversationId);
        try {
            RList<String> redisList = redissonClient.getList(key);
            if (redisList.isExists() && !redisList.isEmpty()) {
                List<ChatMessage> cached = redisList.readAll().stream()
                        .map(json -> JSONUtil.toBean(json, ChatMessage.class))
                        .collect(Collectors.toList());
                log.debug("Redis 缓存命中 - conversationId: {}, 消息数: {}", conversationId, cached.size());
                return cached;
            }
        } catch (Exception e) {
            log.warn("Redis 读取失败，降级到 MySQL - conversationId: {}", conversationId, e);
        }

        List<ChatMessage> history = mysqlStore.loadHistory(conversationId, userId);
        if (CollUtil.isNotEmpty(history)) {
            cacheToRedis(conversationId, history);
        }
        return history;
    }

    @Override
    public Long append(String conversationId, String userId, ChatMessage message) {
        Long messageId = mysqlStore.append(conversationId, userId, message);

        try {
            String key = buildHistoryKey(conversationId);
            RList<String> redisList = redissonClient.getList(key);
            redisList.add(JSONUtil.toJsonStr(message));

            int maxSize = memoryProperties.getHistoryKeepTurns() * 2;
            if (redisList.size() > maxSize) {
                int excess = redisList.size() - maxSize;
                for (int i = 0; i < excess; i++) {
                    redisList.remove(0);
                }
            }
            redisList.expire(Duration.ofMinutes(memoryProperties.getTtlMinutes()));
        } catch (Exception e) {
            log.warn("Redis 追加失败，不影响 MySQL 持久化 - conversationId: {}", conversationId, e);
        }

        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        List<ChatMessage> history = mysqlStore.loadHistory(conversationId, userId);
        cacheToRedis(conversationId, history);
    }

    private void cacheToRedis(String conversationId, List<ChatMessage> history) {
        try {
            String key = buildHistoryKey(conversationId);
            RList<String> redisList = redissonClient.getList(key);
            redisList.delete();
            if (CollUtil.isNotEmpty(history)) {
                List<String> jsonList = history.stream()
                        .map(JSONUtil::toJsonStr)
                        .collect(Collectors.toList());
                redisList.addAll(jsonList);
            }
            redisList.expire(Duration.ofMinutes(memoryProperties.getTtlMinutes()));
            log.debug("Redis 缓存写入 - conversationId: {}, 消息数: {}", conversationId,
                    history == null ? 0 : history.size());
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败 - conversationId: {}", conversationId, e);
        }
    }

    private String buildHistoryKey(String conversationId) {
        return HISTORY_KEY_PREFIX + conversationId;
    }
}
