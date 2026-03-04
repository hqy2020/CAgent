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
 * Obsidian MCP 工具 — 更新笔记（追加/前插/日记追加）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_update",
        name = "更新 Obsidian 笔记",
        description = "向已有笔记追加或前插内容，也可向今日日记追加内容",
        examples = {"在日记里追加一条待办", "往 README 笔记末尾添加内容", "在笔记开头插入摘要"},
        requireUserId = false,
        parameters = {
                @MCPParam(name = "content", description = "要追加/前插的内容（Markdown 格式）", type = "string", required = true),
                @MCPParam(name = "file", description = "目标笔记文件名（不含 .md 后缀）", type = "string", required = false),
                @MCPParam(name = "path", description = "目标笔记相对路径", type = "string", required = false),
                @MCPParam(name = "position", description = "插入位置", type = "string", required = false,
                        defaultValue = "append", enumValues = {"append", "prepend"}),
                @MCPParam(name = "daily", description = "是否写入今日日记", type = "string", required = false,
                        defaultValue = "false", enumValues = {"true", "false"}),
                @MCPParam(name = "date", description = "目标日期（YYYY-MM-DD 格式），仅 daily=true 时生效，默认今天", type = "string", required = false)
        }
)
public class ObsidianUpdateNoteTool {

    private final ObsidianCliExecutor cliExecutor;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String content = request.getStringParameter("content");
        if (content == null || content.isBlank()) {
            return MCPResponse.error("obsidian_update", "MISSING_PARAM", "必须提供 content 参数");
        }

        String file = request.getStringParameter("file");
        String path = request.getStringParameter("path");
        String position = request.getStringParameter("position");
        String daily = request.getStringParameter("daily");
        String date = request.getStringParameter("date");

        boolean isDaily = "true".equalsIgnoreCase(daily);
        boolean isPrepend = "prepend".equalsIgnoreCase(position);

        String command;
        if (isDaily) {
            command = "daily:append";
        } else if (isPrepend) {
            command = "prepend";
        } else {
            command = "append";
        }

        List<String> args = new ArrayList<>();
        args.add("content=" + content);
        if (isDaily) {
            if (date != null && !date.isBlank()) {
                args.add("date=" + date);
            }
        } else {
            if (path != null && !path.isBlank()) {
                args.add("path=" + path);
            } else if (file != null && !file.isBlank()) {
                args.add("file=" + file);
            } else {
                return MCPResponse.error("obsidian_update", "MISSING_PARAM", "非日记模式下必须提供 file 或 path 参数");
            }
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute(command, args);
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_update", "CLI_ERROR", result.stderr());
        }
        return MCPResponse.success("obsidian_update", "笔记更新成功。\n" + result.stdout());
    }
}
