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
 * 联网实时信息搜索工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "web_realtime_search",
        name = "联网实时信息搜索",
        description = "联网搜索天气、汇率、股价、票房、比分、赛程、路况等最新实时信息。",
        useWhen = "当用户询问天气、气温、股价、汇率、币价、票房、比分、赛程等时效性事实时优先使用。",
        avoidWhen = "不要用于外部公司介绍、岗位归属说明、长期静态常识，新闻热点优先使用 web_news_search。",
        examples = {
                "今天上海天气怎么样",
                "美元兑人民币汇率是多少",
                "今天比特币价格"
        },
        requireUserId = false,
        operationType = MCPTool.OperationType.READ,
        sceneKeywords = {"联网", "实时", "天气", "行情"},
        timeoutSeconds = 15,
        maxRetries = 1,
        sensitivity = MCPTool.Sensitivity.MEDIUM,
        fallbackMessage = "联网实时查询暂时不可用，请稍后重试。",
        cacheableFallback = true,
        fallbackCacheTtlSeconds = 180,
        parameters = {
                @MCPParam(name = "query", description = "实时查询关键词，应保留主体和查询项", type = "string",
                        required = true, example = "上海天气"),
                @MCPParam(name = "limit", description = "结果条数，默认 4", type = "integer",
                        required = false, defaultValue = "4", example = "4")
        }
)
public class WebRealtimeSearchTool {

    private static final String TOOL_ID = "web_realtime_search";

    private final BrowserMcpRealtimeSearchService browserMcpRealtimeSearchService;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String query = request.getStringParameter("query");
        if (StrUtil.isBlank(query)) {
            query = request.getUserQuestion();
        }
        if (StrUtil.isBlank(query)) {
            return MCPResponse.error(TOOL_ID, "MISSING_PARAM", "必须提供 query 参数");
        }

        int limit = 4;
        String rawLimit = request.getStringParameter("limit");
        if (StrUtil.isNotBlank(rawLimit)) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (Exception ignore) {
                limit = 4;
            }
        }

        BrowserMcpRealtimeSearchService.RealtimeSearchResult result =
                browserMcpRealtimeSearchService.search(query, limit);
        if (!result.success()) {
            return MCPResponse.error(TOOL_ID, "SEARCH_ERROR",
                    StrUtil.blankToDefault(result.message(), "联网实时信息检索失败"));
        }
        return MCPResponse.success(TOOL_ID, result.toText(query), result.toData());
    }
}
