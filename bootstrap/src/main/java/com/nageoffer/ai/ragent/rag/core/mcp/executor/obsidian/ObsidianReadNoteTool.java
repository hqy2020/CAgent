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

package com.nageoffer.ai.ragent.rag.core.mcp.executor.obsidian;

import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.MCPExecute;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.MCPParam;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.MCPToolDeclare;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Obsidian MCP 工具 — 读取笔记内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_read",
        name = "读取 Obsidian 笔记",
        description = "读取指定 Obsidian 笔记的完整内容，适合打开已知笔记或按路径查看全文。",
        useWhen = "当用户已经知道笔记名或路径，想直接打开并查看全文时使用。",
        avoidWhen = "不要用于按关键词搜一批相关笔记，也不要用于列目录或执行写操作。",
        examples = {"读取我的日记", "打开笔记 README", "查看 3-Knowledge 下的 RAG 笔记"},
        sceneKeywords = {"Obsidian", "笔记读取", "知识卡片"},
        requireUserId = false,
        timeoutSeconds = 12,
        maxRetries = 1,
        sensitivity = MCPTool.Sensitivity.LOW,
        fallbackMessage = "Obsidian 读取暂时不可用，请稍后重试。",
        parameters = {
                @MCPParam(name = "file", description = "笔记文件名（不含 .md 后缀）", type = "string",
                        required = false, example = "README"),
                @MCPParam(name = "path", description = "笔记相对路径（如 3-Knowledge/RAG.md）", type = "string",
                        required = false, example = "3-Knowledge/RAG.md")
        }
)
public class ObsidianReadNoteTool {

    private final ObsidianCliExecutor cliExecutor;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String file = request.getStringParameter("file");
        String path = request.getStringParameter("path");

        if ((file == null || file.isBlank()) && (path == null || path.isBlank())) {
            return MCPResponse.error("obsidian_read", "MISSING_PARAM", "必须提供 file 或 path 参数");
        }

        List<String> args = new ArrayList<>();
        if (path != null && !path.isBlank()) {
            args.add("path=" + path);
        } else {
            args.add("file=" + file);
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute("read", args);
        if (result == null) {
            return MCPResponse.error("obsidian_read", "CLI_ERROR", "Obsidian 读取执行器未返回结果");
        }
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_read", "CLI_ERROR", result.stderr());
        }
        MCPResponse response = MCPResponse.success("obsidian_read", result.stdout());
        response.setFallbackUsed(result.stdout().contains("[fallback]"));
        return response;
    }
}
