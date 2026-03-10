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
 * Obsidian MCP 工具 — 列出文件/文件夹
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_list",
        name = "列出 Obsidian 笔记",
        description = "列出 Obsidian 笔记库中的文件或文件夹，适合浏览目录结构。",
        useWhen = "当用户想看某个目录下有哪些文件、文件夹或整体结构时使用。",
        avoidWhen = "不要用于搜索关键词命中结果，也不要用于打开某篇笔记全文或执行写操作。",
        examples = {"列出笔记库里的文件夹", "查看所有笔记", "列出 3-Knowledge 文件夹下的文件"},
        sceneKeywords = {"Obsidian", "目录浏览", "文件列表"},
        requireUserId = false,
        operationType = MCPTool.OperationType.READ,
        timeoutSeconds = 10,
        maxRetries = 1,
        sensitivity = MCPTool.Sensitivity.LOW,
        fallbackMessage = "Obsidian 列表暂时不可用，请稍后重试。",
        parameters = {
                @MCPParam(name = "folder", description = "指定文件夹路径（默认根目录）", type = "string",
                        required = false, example = "3-Knowledge"),
                @MCPParam(name = "type", description = "列出类型：files 表示文件，folders 表示文件夹", type = "string",
                        required = false, defaultValue = "files", example = "folders", enumValues = {"files", "folders"}),
                @MCPParam(name = "ext", description = "文件扩展名过滤（仅 type=files 时有效）", type = "string",
                        required = false, defaultValue = "md", example = "md")
        }
)
public class ObsidianListNotesTool {

    private final ObsidianCliExecutor cliExecutor;
    private final MCPErrorClassifier errorClassifier;

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
        if (result == null) {
            return MCPResponse.error("obsidian_list", "EXECUTION_ERROR", "Obsidian 列表执行器未返回结果");
        }
        if (!result.isSuccess()) {
            return errorClassifier.classifyCliFailure("obsidian_list", result.stderr());
        }
        MCPResponse response = MCPResponse.success("obsidian_list", result.stdout());
        response.setFallbackUsed(result.stdout().contains("[fallback]"));
        return response;
    }
}
