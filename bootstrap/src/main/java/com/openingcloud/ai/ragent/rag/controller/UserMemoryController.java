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

package com.openingcloud.ai.ragent.rag.controller;

import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.web.Results;
import com.openingcloud.ai.ragent.rag.controller.request.UserMemoryUpdateRequest;
import com.openingcloud.ai.ragent.rag.controller.vo.UserMemoryVO;
import com.openingcloud.ai.ragent.rag.core.usermemory.UserMemoryService;
import com.openingcloud.ai.ragent.rag.core.usermemory.MemoryVectorStoreService;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryType;
import com.openingcloud.ai.ragent.rag.dao.entity.UserMemoryDO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserMemoryController {

    private final UserMemoryService userMemoryService;
    private final MemoryVectorStoreService memoryVectorStoreService;

    @GetMapping("/admin/memories")
    public Result<List<UserMemoryVO>> list(@RequestParam Long userId,
                                           @RequestParam(required = false) String type) {
        List<UserMemoryDO> memories;
        if (type != null && !type.isBlank()) {
            memories = userMemoryService.listByType(userId, MemoryType.valueOf(type));
        } else {
            memories = userMemoryService.listActive(userId);
        }
        List<UserMemoryVO> voList = memories.stream().map(this::toVO).toList();
        return Results.success(voList);
    }

    @GetMapping("/admin/memories/{id}")
    public Result<UserMemoryVO> get(@PathVariable Long id) {
        UserMemoryDO memory = userMemoryService.getById(id);
        if (memory == null) {
            return Results.success(null);
        }
        return Results.success(toVO(memory));
    }

    @PutMapping("/admin/memories/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody UserMemoryUpdateRequest request) {
        userMemoryService.update(id, request.getContent());
        // 同步更新向量
        UserMemoryDO updated = userMemoryService.getById(id);
        if (updated != null && request.getContent() != null) {
            memoryVectorStoreService.upsert(id, updated.getUserId(), updated.getContent());
        }
        return Results.success();
    }

    @PostMapping("/admin/memories/{id}/archive")
    public Result<Void> archive(@PathVariable Long id) {
        userMemoryService.archive(id, null);
        memoryVectorStoreService.delete(id);
        return Results.success();
    }

    @DeleteMapping("/admin/memories/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userMemoryService.softDelete(id);
        memoryVectorStoreService.delete(id);
        return Results.success();
    }

    private UserMemoryVO toVO(UserMemoryDO entity) {
        return UserMemoryVO.builder()
                .id(String.valueOf(entity.getId()))
                .userId(String.valueOf(entity.getUserId()))
                .memoryType(entity.getMemoryType())
                .content(entity.getContent())
                .sourceConversationId(entity.getSourceConversationId())
                .weight(entity.getWeight())
                .state(entity.getState())
                .tags(entity.getTags())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
