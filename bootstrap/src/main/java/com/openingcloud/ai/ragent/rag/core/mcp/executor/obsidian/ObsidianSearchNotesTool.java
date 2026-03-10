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

package com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian;

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPExecute;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPParam;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPToolDeclare;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPErrorClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Obsidian MCP 工具 — 搜索笔记
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_search",
        name = "搜索 Obsidian 笔记",
        description = "在 Obsidian 笔记库中按关键词全文搜索，返回匹配笔记及可选上下文片段。",
        useWhen = "当用户想按主题、关键词、标签或术语查找相关笔记时使用。",
        avoidWhen = "不要用于读取某一篇已知笔记全文，也不要用于列出目录结构或执行写操作。",
        examples = {"搜索关于 RAG 的笔记", "在笔记库中查找 Spring Boot", "搜索包含 TODO 的笔记"},
        sceneKeywords = {"Obsidian", "笔记搜索", "全文检索"},
        requireUserId = false,
        operationType = MCPTool.OperationType.READ,
        timeoutSeconds = 12,
        maxRetries = 1,
        sensitivity = MCPTool.Sensitivity.LOW,
        fallbackMessage = "Obsidian 搜索暂时不可用，请稍后重试。",
        parameters = {
                @MCPParam(name = "query", description = "搜索关键词", type = "string", required = true, example = "HashMap"),
                @MCPParam(name = "path", description = "限定搜索的文件夹路径", type = "string", required = false,
                        example = "3-Knowledge/Java"),
                @MCPParam(name = "limit", description = "返回结果数量上限", type = "integer", required = false,
                        defaultValue = "10", example = "5"),
                @MCPParam(name = "withContext", description = "是否返回匹配上下文", type = "string", required = false,
                        defaultValue = "true", example = "true", enumValues = {"true", "false"})
        }
)
public class ObsidianSearchNotesTool {

    private final ObsidianCliExecutor cliExecutor;
    private final MCPErrorClassifier errorClassifier;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String query = request.getStringParameter("query");
        if (query == null || query.isBlank()) {
            return MCPResponse.error("obsidian_search", "MISSING_PARAM", "必须提供 query 参数");
        }

        String path = request.getStringParameter("path");
        String limit = request.getStringParameter("limit");
        String withContext = request.getStringParameter("withContext");

        boolean useContext = withContext == null || withContext.isBlank() || "true".equalsIgnoreCase(withContext);
        String command = useContext ? "search:context" : "search";

        List<String> args = new ArrayList<>();
        args.add("query=" + query);
        if (path != null && !path.isBlank()) {
            args.add("path=" + path);
        }
        if (limit != null && !limit.isBlank()) {
            args.add("limit=" + limit);
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute(command, args);
        if (result == null) {
            return MCPResponse.error("obsidian_search", "EXECUTION_ERROR", "Obsidian 搜索执行器未返回结果");
        }
        if (!result.isSuccess()) {
            return errorClassifier.classifyCliFailure("obsidian_search", result.stderr());
        }
        MCPResponse response = MCPResponse.success("obsidian_search", result.stdout());
        response.setFallbackUsed(result.stdout().contains("[fallback]"));
        return response;
    }
}
