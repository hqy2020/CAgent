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

import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务集成测试。
 */
@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MCPServiceTests {

    private final MCPToolRegistry toolRegistry;
    @SuppressWarnings("unused")
    private final MCPService mcpService;

    @Test
    public void testToolRegistryAutoDiscovery() {
        List<MCPTool> tools = toolRegistry.listAllTools();

        assertTrue(tools.size() >= 1, "应至少注册 1 个工具");
        assertTrue(toolRegistry.contains("obsidian_search"), "应包含 Obsidian 搜索工具");
        assertTrue(toolRegistry.contains("web_search"), "应包含联网网页搜索工具");
        assertTrue(toolRegistry.contains("web_news_search"), "应包含联网新闻搜索工具");
        assertFalse(toolRegistry.contains("sales_query"), "销售工具已下线，不应继续注册");
        assertFalse(toolRegistry.contains("attendance_query"), "考勤工具已下线，不应继续注册");
    }
}
