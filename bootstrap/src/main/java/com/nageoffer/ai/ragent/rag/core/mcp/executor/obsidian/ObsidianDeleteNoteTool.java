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
 * Obsidian MCP 工具 — 删除笔记
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_delete",
        name = "删除 Obsidian 笔记",
        description = "删除 Obsidian 笔记库中的指定笔记（默认移到回收站）",
        examples = {"删除草稿笔记", "把 temp 笔记删掉", "永久删除 test 笔记"},
        requireUserId = false,
        parameters = {
                @MCPParam(name = "file", description = "笔记文件名（不含 .md 后缀）", type = "string", required = false),
                @MCPParam(name = "path", description = "笔记相对路径", type = "string", required = false),
                @MCPParam(name = "permanent", description = "是否永久删除（默认移到回收站）", type = "string",
                        required = false, defaultValue = "false", enumValues = {"true", "false"})
        }
)
public class ObsidianDeleteNoteTool {

    private final ObsidianCliExecutor cliExecutor;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String file = request.getStringParameter("file");
        String path = request.getStringParameter("path");

        if ((file == null || file.isBlank()) && (path == null || path.isBlank())) {
            return MCPResponse.error("obsidian_delete", "MISSING_PARAM", "必须提供 file 或 path 参数");
        }

        String permanent = request.getStringParameter("permanent");

        List<String> args = new ArrayList<>();
        if (path != null && !path.isBlank()) {
            args.add("path=" + path);
        } else {
            args.add("file=" + file);
        }
        if ("true".equalsIgnoreCase(permanent)) {
            args.add("permanent=true");
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute("delete", args);
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_delete", "CLI_ERROR", result.stderr());
        }
        return MCPResponse.success("obsidian_delete", "笔记已删除。\n" + result.stdout());
    }
}
