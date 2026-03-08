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

package com.nageoffer.ai.ragent.rag.core.mcp.governance;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import org.springframework.stereotype.Component;

/**
 * MCP 降级响应解析器。
 */
@Component
public class MCPFallbackResolver {

    private final MCPFallbackCache fallbackCache;

    public MCPFallbackResolver(MCPFallbackCache fallbackCache) {
        this.fallbackCache = fallbackCache;
    }

    public MCPResponse resolve(MCPRequest request, MCPTool tool, MCPResponse originalResponse) {
        if (tool == null || originalResponse == null || tool.getOperationType() == MCPTool.OperationType.WRITE) {
            return originalResponse;
        }
        MCPResponse cached = fallbackCache.load(request, tool);
        if (cached != null) {
            if (StrUtil.isBlank(cached.getTextResult())) {
                cached.setTextResult("已返回最近一次可用结果。");
            } else if (!cached.getTextResult().startsWith("[fallback]")) {
                cached.setTextResult("[fallback] 已返回最近一次可用结果。\n" + cached.getTextResult());
            }
            cached.setFallbackUsed(true);
            return cached;
        }
        String fallbackMessage = StrUtil.blankToDefault(tool.getFallbackMessage(), originalResponse.getErrorMessage());
        return MCPResponse.builder()
                .success(false)
                .toolId(tool.getToolId())
                .errorCode(StrUtil.blankToDefault(originalResponse.getErrorCode(), "SYSTEM_ERROR"))
                .standardErrorCode(StrUtil.blankToDefault(originalResponse.getStandardErrorCode(), "SYSTEM_ERROR"))
                .errorMessage(fallbackMessage)
                .errorType(originalResponse.getErrorType())
                .retryable(originalResponse.isRetryable())
                .fallbackUsed(true)
                .userActionHint(StrUtil.blankToDefault(originalResponse.getUserActionHint(), "请稍后重试。"))
                .build();
    }

    public void cacheSuccess(MCPRequest request, MCPTool tool, MCPResponse response) {
        fallbackCache.cacheSuccess(request, tool, response);
    }
}
