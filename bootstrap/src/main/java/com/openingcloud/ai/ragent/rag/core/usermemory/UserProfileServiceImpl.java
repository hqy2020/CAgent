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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.rag.dao.entity.UserProfileDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final String PROFILE_CACHE_PREFIX = "ragent:memory:profile:";
    private static final long PROFILE_CACHE_TTL_MINUTES = 30;

    private final UserProfileMapper userProfileMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public UserProfileDO loadOrCreate(Long userId) {
        UserProfileDO profile = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .eq(UserProfileDO::getDeleted, 0));

        if (profile != null) {
            return profile;
        }

        // 首次登录，创建默认画像（基于 Obsidian AI画像目录的预提取摘要）
        profile = UserProfileDO.builder()
                .userId(userId)
                .displayName("启云")
                .occupation("后端工程师 / 计算机专业学生")
                .interests("[\"Java\",\"Spring Boot\",\"RAG\",\"AI大模型\",\"第二大脑\",\"分布式系统\"]")
                .preferences("{\"answer_style\":\"简洁\",\"language\":\"中文\"}")
                .facts("[\"使用 Java 17 + Spring Boot 3.x\",\"正在开发 RAgent 第二大脑项目\",\"使用 Obsidian 作为个人知识管理工具\"]")
                .summary("启云是一名后端工程师/计算机专业学生，专注于 Java 技术栈和 AI 应用开发，正在构建个人第二大脑系统(RAgent)，使用 Obsidian 进行知识管理。")
                .version(1)
                .build();
        userProfileMapper.insert(profile);
        log.info("创建默认用户画像: userId={}, profileId={}", userId, profile.getId());
        return profile;
    }

    @Override
    public UserProfileDO update(UserProfileDO profile) {
        profile.setVersion(profile.getVersion() + 1);
        userProfileMapper.updateById(profile);
        // 清除缓存
        stringRedisTemplate.delete(PROFILE_CACHE_PREFIX + profile.getUserId());
        return profile;
    }

    @Override
    public String formatForPrompt(UserProfileDO profile) {
        if (profile == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户画像\n");
        if (StrUtil.isNotBlank(profile.getDisplayName())) {
            sb.append("- 称呼：").append(profile.getDisplayName()).append("\n");
        }
        if (StrUtil.isNotBlank(profile.getOccupation())) {
            sb.append("- 职业：").append(profile.getOccupation()).append("\n");
        }
        if (StrUtil.isNotBlank(profile.getSummary())) {
            sb.append("- 简介：").append(profile.getSummary()).append("\n");
        }
        if (StrUtil.isNotBlank(profile.getInterests())) {
            sb.append("- 兴趣领域：").append(profile.getInterests()).append("\n");
        }
        if (StrUtil.isNotBlank(profile.getFacts())) {
            sb.append("- 已知事实：").append(profile.getFacts()).append("\n");
        }
        if (StrUtil.isNotBlank(profile.getPreferences())) {
            sb.append("- 偏好：").append(profile.getPreferences()).append("\n");
        }
        return sb.toString().trim();
    }
}
