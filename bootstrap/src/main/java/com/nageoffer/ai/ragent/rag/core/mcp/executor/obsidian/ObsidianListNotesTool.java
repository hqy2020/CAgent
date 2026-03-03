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
 * Obsidian MCP 工具 — 列出文件/文件夹
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_list",
        name = "列出 Obsidian 笔记",
        description = "列出 Obsidian 笔记库中的文件或文件夹",
        examples = {"列出笔记库里的文件夹", "查看所有笔记", "列出 3-Knowledge 文件夹下的文件"},
        requireUserId = false,
        parameters = {
                @MCPParam(name = "folder", description = "指定文件夹路径（默认根目录）", type = "string", required = false),
                @MCPParam(name = "type", description = "列出类型", type = "string", required = false,
                        defaultValue = "files", enumValues = {"files", "folders"}),
                @MCPParam(name = "ext", description = "文件扩展名过滤（仅 type=files 时有效）", type = "string",
                        required = false, defaultValue = "md")
        }
)
public class ObsidianListNotesTool {

    private final ObsidianCliExecutor cliExecutor;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String folder = request.getStringParameter("folder");
        String type = request.getStringParameter("type");
        String ext = request.getStringParameter("ext");

        String command = "folders".equalsIgnoreCase(type) ? "folders" : "files";

        List<String> args = new ArrayList<>();
        if (folder != null && !folder.isBlank()) {
            args.add("folder=" + folder);
        }
        if ("files".equals(command) && ext != null && !ext.isBlank()) {
            args.add("ext=" + ext);
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute(command, args);
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_list", "CLI_ERROR", result.stderr());
        }
        return MCPResponse.success("obsidian_list", result.stdout());
    }
}
