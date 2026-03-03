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
        description = "读取 Obsidian 笔记库中指定笔记的完整内容",
        examples = {"读取我的日记", "打开笔记 README", "查看 3-Knowledge 下的 RAG 笔记"},
        requireUserId = false,
        parameters = {
                @MCPParam(name = "file", description = "笔记文件名（不含 .md 后缀）", type = "string", required = false),
                @MCPParam(name = "path", description = "笔记相对路径（如 3-Knowledge/RAG.md）", type = "string", required = false)
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
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_read", "CLI_ERROR", result.stderr());
        }
        return MCPResponse.success("obsidian_read", result.stdout());
    }
}
