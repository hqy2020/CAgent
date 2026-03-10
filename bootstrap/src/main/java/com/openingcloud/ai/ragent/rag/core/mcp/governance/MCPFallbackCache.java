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

package com.openingcloud.ai.ragent.rag.core.mcp.governance;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 降级缓存。
 */
@Slf4j
@Component
public class MCPFallbackCache {

    private static final String CACHE_KEY_PREFIX = "rag:mcp:fallback:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RagMcpExecutionProperties properties;

    @Autowired
    public MCPFallbackCache(ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                            ObjectMapper objectMapper,
                            RagMcpExecutionProperties properties) {
        this.stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MCPFallbackCache(StringRedisTemplate stringRedisTemplate,
                            ObjectMapper objectMapper,
                            RagMcpExecutionProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void cacheSuccess(MCPRequest request, MCPTool tool, MCPResponse response) {
        if (!isCacheable(tool) || stringRedisTemplate == null || request == null || response == null
                || !response.isSuccess() || response.isFallbackUsed()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            int ttlSeconds = tool.getFallbackCacheTtlSeconds() > 0
                    ? tool.getFallbackCacheTtlSeconds()
                    : properties.getDefaultFallbackCacheTtlSeconds();
            stringRedisTemplate.opsForValue().set(buildKey(request), json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Cache MCP fallback result failed, toolId={}", tool == null ? null : tool.getToolId(), e);
        }
    }

    public MCPResponse load(MCPRequest request, MCPTool tool) {
        if (!isCacheable(tool) || stringRedisTemplate == null || request == null) {
            return null;
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(buildKey(request));
            if (StrUtil.isBlank(cached)) {
                return null;
            }
            MCPResponse response = objectMapper.readValue(cached, MCPResponse.class);
            response.setFallbackUsed(true);
            return response;
        } catch (Exception e) {
            log.warn("Load MCP fallback result failed, toolId={}", tool == null ? null : tool.getToolId(), e);
            return null;
        }
    }

    private boolean isCacheable(MCPTool tool) {
        return tool != null && tool.isCacheableFallback();
    }

    private String buildKey(MCPRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolId", request.getToolId());
        payload.put("parameters", request.getParameters());
        payload.put("question", request.getUserQuestion());
        return CACHE_KEY_PREFIX + request.getToolId() + ":" + DigestUtil.md5Hex(toStableJson(payload));
    }

    private String toStableJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}
