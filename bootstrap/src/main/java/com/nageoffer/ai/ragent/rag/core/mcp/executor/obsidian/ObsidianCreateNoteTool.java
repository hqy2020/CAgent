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
 * Obsidian MCP 工具 — 创建笔记
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_create",
        name = "创建 Obsidian 笔记",
        description = "在 Obsidian 笔记库中创建新笔记，可指定路径、内容和模板",
        examples = {"创建一个关于 Docker 的笔记", "在 3-Knowledge 下新建 Spring 笔记", "用日记模板创建今天的笔记"},
        requireUserId = false,
        parameters = {
                @MCPParam(name = "name", description = "笔记名称（不含 .md 后缀）", type = "string", required = true),
                @MCPParam(name = "path", description = "目标文件夹路径", type = "string", required = false),
                @MCPParam(name = "content", description = "笔记初始内容（Markdown 格式）", type = "string", required = false),
                @MCPParam(name = "template", description = "使用的模板名称", type = "string", required = false)
        }
)
public class ObsidianCreateNoteTool {

    private final ObsidianCliExecutor cliExecutor;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String name = request.getStringParameter("name");
        if (name == null || name.isBlank()) {
            return MCPResponse.error("obsidian_create", "MISSING_PARAM", "必须提供 name 参数");
        }

        String path = request.getStringParameter("path");
        String content = request.getStringParameter("content");
        String template = request.getStringParameter("template");

        List<String> args = new ArrayList<>();
        args.add("name=" + name);
        if (path != null && !path.isBlank()) {
            args.add("path=" + path);
        }
        if (content != null && !content.isBlank()) {
            args.add("content=" + content);
        }
        if (template != null && !template.isBlank()) {
            args.add("template=" + template);
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute("create", args);
        if (!result.isSuccess()) {
            return MCPResponse.error("obsidian_create", "CLI_ERROR", result.stderr());
        }
        return MCPResponse.success("obsidian_create", "笔记「" + name + "」创建成功。\n" + result.stdout());
    }
}
