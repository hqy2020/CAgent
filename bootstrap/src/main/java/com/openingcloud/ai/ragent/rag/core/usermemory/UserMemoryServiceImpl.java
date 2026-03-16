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

package com.openingcloud.ai.ragent.rag.core.usermemory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryState;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryType;
import com.openingcloud.ai.ragent.rag.dao.entity.UserMemoryDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.UserMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryServiceImpl implements UserMemoryService {

    private final UserMemoryMapper userMemoryMapper;

    @Override
    public UserMemoryDO save(Long userId, MemoryType type, String content,
                             String conversationId, Long messageId) {
        BigDecimal weight = switch (type) {
            case PINNED -> new BigDecimal("1.20");
            case INSIGHT -> new BigDecimal("1.00");
            case DIGEST -> new BigDecimal("0.80");
        };

        UserMemoryDO memory = UserMemoryDO.builder()
                .userId(userId)
                .memoryType(type.name())
                .content(content)
                .sourceConversationId(conversationId)
                .sourceMessageId(messageId)
                .weight(weight)
                .state(MemoryState.ACTIVE.name())
                .build();
        userMemoryMapper.insert(memory);
        log.info("保存用户记忆: userId={}, type={}, memoryId={}", userId, type, memory.getId());
        return memory;
    }

    @Override
    public List<UserMemoryDO> listActive(Long userId) {
        return userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getUserId, userId)
                .eq(UserMemoryDO::getState, MemoryState.ACTIVE.name())
                .eq(UserMemoryDO::getDeleted, 0)
                .orderByDesc(UserMemoryDO::getCreateTime));
    }

    @Override
    public List<UserMemoryDO> listByType(Long userId, MemoryType type) {
        return userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getUserId, userId)
                .eq(UserMemoryDO::getMemoryType, type.name())
                .eq(UserMemoryDO::getState, MemoryState.ACTIVE.name())
                .eq(UserMemoryDO::getDeleted, 0)
                .orderByDesc(UserMemoryDO::getCreateTime));
    }

    @Override
    public void archive(Long memoryId, Long supersededBy) {
        userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getId, memoryId)
                .set(UserMemoryDO::getState, MemoryState.ARCHIVED.name())
                .set(UserMemoryDO::getSupersededBy, supersededBy));
    }

    @Override
    public void softDelete(Long memoryId) {
        userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getId, memoryId)
                .set(UserMemoryDO::getState, MemoryState.DELETED.name()));
    }

    @Override
    public UserMemoryDO getById(Long memoryId) {
        return userMemoryMapper.selectById(memoryId);
    }

    @Override
    public UserMemoryDO update(Long memoryId, String content) {
        userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getId, memoryId)
                .set(UserMemoryDO::getContent, content));
        return userMemoryMapper.selectById(memoryId);
    }
}
