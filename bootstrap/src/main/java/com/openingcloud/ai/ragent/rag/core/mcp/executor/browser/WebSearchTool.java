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

package com.openingcloud.ai.ragent.rag.core.mcp.executor.browser;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPExecute;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPParam;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPToolDeclare;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用联网网页搜索工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "web_search",
        name = "联网网页搜索",
        description = "联网搜索外部网页结果，适合查询公司、团队、平台、业务实体的介绍、归属关系与公开说明。",
        useWhen = "当用户询问某个外部实体是什么、做什么的、属于哪个公司、属于哪个业务，或显式要求联网搜索网页信息时优先使用。",
        avoidWhen = "不要用于查询今日日报、实时热点新闻，新闻场景优先使用 web_news_search；也不要用于读取本地知识库或 Obsidian。",
        examples = {
                "1688-JAVA-工厂技术 做什么的",
                "阿里云百炼是什么",
                "帮我联网搜索飞书属于哪个公司"
        },
        requireUserId = false,
        operationType = MCPTool.OperationType.READ,
        sceneKeywords = {"联网", "实体介绍", "网页搜索"},
        timeoutSeconds = 15,
        maxRetries = 1,
        sensitivity = MCPTool.Sensitivity.MEDIUM,
        fallbackMessage = "联网网页检索暂时不可用，请稍后重试。",
        cacheableFallback = true,
        fallbackCacheTtlSeconds = 300,
        parameters = {
                @MCPParam(name = "query", description = "网页检索关键词，应保留实体名称与核心问法", type = "string",
                        required = true, example = "1688-JAVA-工厂技术 做什么的"),
                @MCPParam(name = "limit", description = "结果条数，默认 5", type = "integer",
                        required = false, defaultValue = "5", example = "5")
        }
)
public class WebSearchTool {

    private static final String TOOL_ID = "web_search";

    private final BrowserMcpWebSearchService browserMcpWebSearchService;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String query = request.getStringParameter("query");
        if (StrUtil.isBlank(query)) {
            query = request.getUserQuestion();
        }
        if (StrUtil.isBlank(query)) {
            return MCPResponse.error(TOOL_ID, "MISSING_PARAM", "必须提供 query 参数");
        }

        int limit = 5;
        String rawLimit = request.getStringParameter("limit");
        if (StrUtil.isNotBlank(rawLimit)) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (Exception ignore) {
                limit = 5;
            }
        }

        BrowserMcpWebSearchService.WebSearchResult result = browserMcpWebSearchService.search(query, limit);
        if (!result.success()) {
            return MCPResponse.error(TOOL_ID, "SEARCH_ERROR", StrUtil.blankToDefault(result.message(), "联网网页检索失败"));
        }
        return MCPResponse.success(TOOL_ID, result.toText(query), result.toData());
    }
}
