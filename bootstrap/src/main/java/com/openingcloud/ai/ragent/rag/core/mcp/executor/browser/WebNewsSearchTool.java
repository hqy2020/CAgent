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
 * 多源联网热点搜索工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "web_news_search",
        name = "联网新闻搜索",
        description = "联网搜索近期新闻与热点，聚合多来源结果并返回标题、来源、链接和发布时间。",
        useWhen = "当用户询问今天/最近的新闻、热点、最新动态、实时资讯并要求给出处链接时优先使用。",
        avoidWhen = "不要用于查询个人知识库、离线笔记内容、固定常识或本地项目文档。",
        examples = {
                "帮我联网搜索今天 AI 领域的 3 条新闻",
                "联网查一下今天大模型领域最新动态",
                "搜索今天的 AI 热点并给出处链接"
        },
        requireUserId = false,
        operationType = MCPTool.OperationType.READ,
        sceneKeywords = {"联网", "实时新闻", "热点"},
        timeoutSeconds = 15,
        maxRetries = 1,
        sensitivity = MCPTool.Sensitivity.MEDIUM,
        fallbackMessage = "联网检索暂时不可用，请稍后重试。",
        cacheableFallback = true,
        fallbackCacheTtlSeconds = 300,
        parameters = {
                @MCPParam(name = "query", description = "新闻检索关键词，应包含主题或领域", type = "string",
                        required = true, example = "AI 大模型"),
                @MCPParam(name = "limit", description = "结果条数，默认 3", type = "integer",
                        required = false, defaultValue = "3", example = "3")
        }
)
public class WebNewsSearchTool {

    private static final String TOOL_ID = "web_news_search";

    private final BrowserMcpNewsService browserMcpNewsService;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String query = request.getStringParameter("query");
        if (StrUtil.isBlank(query)) {
            query = request.getUserQuestion();
        }
        if (StrUtil.isBlank(query)) {
            return MCPResponse.error(TOOL_ID, "MISSING_PARAM", "必须提供 query 参数");
        }

        int limit = 3;
        String rawLimit = request.getStringParameter("limit");
        if (StrUtil.isNotBlank(rawLimit)) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (Exception ignore) {
                limit = 3;
            }
        }

        BrowserMcpNewsService.NewsSearchResult result = browserMcpNewsService.search(query, limit);
        if (!result.success()) {
            return MCPResponse.error(TOOL_ID, "SEARCH_ERROR", StrUtil.blankToDefault(result.message(), "联网检索失败"));
        }
        MCPResponse response = MCPResponse.success(TOOL_ID, result.toText(query), result.toData());
        response.setFallbackUsed(result.fallback());
        return response;
    }
}
