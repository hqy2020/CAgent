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

package com.openingcloud.ai.ragent.rag.mcp;

import com.openingcloud.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPPayloadSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPPayloadSanitizerTests {

    @Test
    void shouldMaskSensitiveFieldsAndTruncateLongText() {
        RagMcpExecutionProperties properties = new RagMcpExecutionProperties();
        properties.setAuditPayloadMaxChars(16);
        MCPPayloadSanitizer sanitizer = new MCPPayloadSanitizer(properties);

        MCPTool tool = MCPTool.builder()
                .toolId("obsidian_update")
                .sensitiveParams(List.of("content"))
                .build();
        MCPRequest request = MCPRequest.builder()
                .toolId("obsidian_update")
                .userQuestion("这是一段很长的问题文本，会被记录长度。")
                .parameters(Map.of(
                        "content", "super secret markdown body",
                        "apiKey", "abc123456",
                        "title", "这是一个会被截断的超长标题文本并且长度明显超过限制"
                ))
                .build();
        MCPResponse response = MCPResponse.success("obsidian_update", "这是一个很长的返回文本，用来验证截断是否生效。");
        response.setData(Map.of(
                "content", "sensitive result",
                "notePath", "3-Knowledge/demo.md"
        ));

        Map<String, Object> sanitizedRequest = sanitizer.sanitizeRequest(request, tool);
        Map<String, Object> sanitizedResponse = sanitizer.sanitizeResponse(response, tool);
        Map<?, ?> sanitizedParams = (Map<?, ?>) sanitizedRequest.get("parameters");
        Map<?, ?> sanitizedData = (Map<?, ?>) sanitizedResponse.get("data");

        assertEquals("[MASKED length=26]", sanitizedParams.get("content"));
        assertEquals("[MASKED length=9]", sanitizedParams.get("apiKey"));
        assertTrue(String.valueOf(sanitizedParams.get("title")).contains("...(truncated)"));
        assertEquals(request.getUserQuestion().length(), sanitizedRequest.get("questionLength"));
        assertEquals("[MASKED length=16]", sanitizedData.get("content"));
        assertTrue(String.valueOf(sanitizedData.get("notePath")).contains("...(truncated)"));
    }
}
