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
import com.openingcloud.ai.ragent.rag.controller.request.UserProfileUpdateRequest;
import com.openingcloud.ai.ragent.rag.controller.vo.UserProfileVO;
import com.openingcloud.ai.ragent.rag.core.usermemory.UserProfileService;
import com.openingcloud.ai.ragent.rag.dao.entity.UserProfileDO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping("/admin/profiles")
    public Result<UserProfileVO> get(@RequestParam Long userId) {
        UserProfileDO profile = userProfileService.loadOrCreate(userId);
        return Results.success(toVO(profile));
    }

    @PutMapping("/admin/profiles")
    public Result<Void> update(@RequestParam Long userId,
                               @RequestBody UserProfileUpdateRequest request) {
        UserProfileDO existing = userProfileService.loadOrCreate(userId);
        if (request.getDisplayName() != null) {
            existing.setDisplayName(request.getDisplayName());
        }
        if (request.getOccupation() != null) {
            existing.setOccupation(request.getOccupation());
        }
        if (request.getInterests() != null) {
            existing.setInterests(request.getInterests());
        }
        if (request.getPreferences() != null) {
            existing.setPreferences(request.getPreferences());
        }
        if (request.getFacts() != null) {
            existing.setFacts(request.getFacts());
        }
        if (request.getSummary() != null) {
            existing.setSummary(request.getSummary());
        }
        userProfileService.update(existing);
        return Results.success();
    }

    private UserProfileVO toVO(UserProfileDO entity) {
        return UserProfileVO.builder()
                .id(String.valueOf(entity.getId()))
                .userId(String.valueOf(entity.getUserId()))
                .displayName(entity.getDisplayName())
                .occupation(entity.getOccupation())
                .interests(entity.getInterests())
                .preferences(entity.getPreferences())
                .facts(entity.getFacts())
                .summary(entity.getSummary())
                .version(entity.getVersion())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
