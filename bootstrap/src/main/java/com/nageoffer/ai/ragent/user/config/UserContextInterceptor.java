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

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 用户上下文拦截器
 *
 * <p>该拦截器用于在请求处理前从 SaToken 中获取登录用户信息，并设置到 UserContext 中，
 * 方便后续业务逻辑使用。在请求完成后清理 UserContext，避免内存泄漏。
 *
 * <p>主要功能：
 * <ul>
 *   <li>在请求前置处理时，从 SaToken 获取登录用户 ID</li>
 *   <li>根据用户 ID 查询数据库获取完整用户信息</li>
 *   <li>将用户信息封装成 LoginUser 对象并设置到 UserContext 线程上下文中</li>
 *   <li>在请求完成后清理 UserContext，防止线程复用时的数据污染</li>
 *   <li>跳过异步调度请求（如 SSE 完成回调），避免 SaToken 上下文丢失问题</li>
 * </ul>
 *
 * @author openingClouds
 * @see UserContext
 * @see LoginUser
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private static final String ADMIN_USERNAME = "admin";
    private static final String DEFAULT_AVATAR_URL = "https://raw.githubusercontent.com/hqy2020/CAgent/main/logo.png";
    private static final Set<String> LEGACY_DEFAULT_AVATAR_URLS = Set.of(
            "https://avatars.githubusercontent.com/u/583231?v=4",
            "https://static.deepseek.com/user-avatar/G_6cuD8GbD53VwGRwisvCsZ6"
    );

    private final UserMapper userMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        // 仅在初始 REQUEST 派发阶段读取登录态，避免 SSE 的 ASYNC/ERROR 派发触发 SaToken 上下文缺失
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        // 预检请求放行，避免 CORS 阻断
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String requestUri = request.getRequestURI();
        if (requestUri.startsWith("/api/ragent/actuator/health")
                || requestUri.startsWith("/api/ragent/actuator/prometheus")
                || requestUri.startsWith("/actuator/health")
                || requestUri.startsWith("/actuator/prometheus")) {
            return true;
        }

        String loginId = StpUtil.getLoginIdAsString();
        if (StrUtil.isBlank(loginId) || !StrUtil.isNumeric(loginId)) {
            return true;
        }
        UserDO user = userMapper.selectById(Long.parseLong(loginId));
        if (user == null) {
            return true;
        }

        UserContext.set(
                LoginUser.builder()
                        .userId(user.getId().toString())
                        .username(user.getUsername())
                        .role(user.getRole())
                        .avatar(resolveAvatar(user.getUsername(), user.getAvatar()))
                        .build()
        );
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveAvatar(String username, String avatar) {
        if (StrUtil.equalsIgnoreCase(ADMIN_USERNAME, StrUtil.trimToEmpty(username))) {
            return DEFAULT_AVATAR_URL;
        }
        String normalized = StrUtil.trimToNull(avatar);
        if (normalized == null || LEGACY_DEFAULT_AVATAR_URLS.contains(normalized)) {
            return DEFAULT_AVATAR_URL;
        }
        return normalized;
    }
}
