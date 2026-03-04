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
 * Obsidian MCP 工具 — 替换笔记内容（语义编辑）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_replace",
        name = "替换 Obsidian 笔记内容",
        description = "替换 Obsidian 笔记中的指定文本为新内容，支持按文件名或路径定位笔记",
        examples = {"把笔记里的旧标题替换为新标题", "修改笔记中的某段描述", "将笔记中的 A 替换为 B"},
        requireUserId = false,
        parameters = {
                @MCPParam(name = "file", description = "目标笔记文件名（不含 .md 后缀）", type = "string", required = false),
                @MCPParam(name = "path", description = "目标笔记相对路径", type = "string", required = false),
                @MCPParam(name = "oldContent", description = "要替换的原文本", type = "string", required = true),
                @MCPParam(name = "newContent", description = "替换后的新文本", type = "string", required = true)
        }
)
public class ObsidianReplaceNoteTool {

    private final ObsidianCliExecutor cliExecutor;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String file = request.getStringParameter("file");
        String path = request.getStringParameter("path");
        String oldContent = request.getStringParameter("oldContent");
        String newContent = request.getStringParameter("newContent");

        if (oldContent == null || oldContent.isBlank()) {
            return MCPResponse.error("obsidian_replace", "MISSING_PARAM", "必须提供 oldContent 参数");
        }
        if (newContent == null) {
            return MCPResponse.error("obsidian_replace", "MISSING_PARAM", "必须提供 newContent 参数");
        }
        if ((file == null || file.isBlank()) && (path == null || path.isBlank())) {
            return MCPResponse.error("obsidian_replace", "MISSING_PARAM", "必须提供 file 或 path 参数");
        }

        List<String> args = new ArrayList<>();
        if (path != null && !path.isBlank()) {
            args.add("path=" + path);
        } else {
            args.add("file=" + file);
        }
        args.add("oldContent=" + oldContent);
        args.add("newContent=" + newContent);

        ObsidianCliExecutor.CliResult result = cliExecutor.execute("replace", args);
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_replace", "REPLACE_ERROR", result.stderr());
        }
        return MCPResponse.success("obsidian_replace", result.stdout());
    }
}
