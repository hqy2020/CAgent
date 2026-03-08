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
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequestSource;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 执行前安全校验。
 */
@Component
public class MCPToolSecurityGuard {

    private static final Set<String> PATH_LIKE_KEYS = Set.of("path", "file", "cwd");

    public MCPResponse validate(MCPRequest request, MCPTool tool) {
        if (request == null || tool == null) {
            return null;
        }
        if (tool.isRequireUserId() && StrUtil.isBlank(request.getUserId())) {
            return MCPResponse.error(tool.getToolId(), "USER_ID_REQUIRED", "该工具需要用户身份信息",
                    MCPResponse.ErrorType.PERMISSION, false);
        }
        if (tool.getOperationType() == MCPTool.OperationType.WRITE
                && request.getRequestSource() == MCPRequestSource.RETRIEVAL) {
            return MCPResponse.error(tool.getToolId(), "RETRIEVAL_WRITE_BLOCKED", "检索阶段禁止执行写工具",
                    MCPResponse.ErrorType.PERMISSION, false);
        }
        if (tool.isConfirmationRequired() && !request.isConfirmed()) {
            return MCPResponse.error(tool.getToolId(), "CONFIRMATION_REQUIRED", "该工具必须经确认链后执行",
                    MCPResponse.ErrorType.PERMISSION, false);
        }
        if (request.getParameters() == null || request.getParameters().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : request.getParameters().entrySet()) {
            String key = normalize(entry.getKey());
            Object value = entry.getValue();
            if (!(value instanceof String text)) {
                continue;
            }
            if (text.contains("\0")) {
                return MCPResponse.error(tool.getToolId(), "SECURITY_VIOLATION", "参数包含非法字符",
                        MCPResponse.ErrorType.SECURITY, false);
            }
            if (PATH_LIKE_KEYS.contains(key)) {
                String trimmed = text.trim();
                if (trimmed.startsWith("/") || trimmed.startsWith("..") || trimmed.contains("../")) {
                    return MCPResponse.error(tool.getToolId(), "SECURITY_VIOLATION", "路径参数不合法",
                            MCPResponse.ErrorType.SECURITY, false);
                }
            }
        }
        return null;
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }
}
