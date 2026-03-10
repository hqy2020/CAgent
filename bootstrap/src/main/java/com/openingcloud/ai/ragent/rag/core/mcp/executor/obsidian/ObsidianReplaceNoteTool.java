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
 * Obsidian MCP 工具 — 替换笔记内容（语义编辑）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_replace",
        name = "替换 Obsidian 笔记内容",
        description = "替换指定 Obsidian 笔记中的旧文本为新文本，适合精确改写局部内容。",
        useWhen = "当用户明确给出旧文本和新文本，想对已有笔记做局部替换时使用。",
        avoidWhen = "不要用于追加新内容、创建新笔记、删除笔记或仅按关键词搜索笔记。",
        examples = {"把笔记里的旧标题替换为新标题", "修改笔记中的某段描述", "将笔记中的 A 替换为 B"},
        requireUserId = true,
        operationType = MCPTool.OperationType.WRITE,
        confirmationRequired = true,
        timeoutSeconds = 15,
        sensitivity = MCPTool.Sensitivity.HIGH,
        sensitiveParams = {"oldContent", "newContent"},
        fallbackMessage = "Obsidian 替换暂时不可用，本次不会执行替换。",
        parameters = {
                @MCPParam(name = "file", description = "目标笔记文件名（不含 .md 后缀）", type = "string",
                        required = false, example = "README"),
                @MCPParam(name = "path", description = "目标笔记相对路径", type = "string", required = false,
                        example = "Projects/Ragent/README.md"),
                @MCPParam(name = "oldContent", description = "要替换的原文本", type = "string",
                        required = true, example = "旧标题"),
                @MCPParam(name = "newContent", description = "替换后的新文本", type = "string",
                        required = true, example = "新标题")
        }
)
public class ObsidianReplaceNoteTool {

    private final ObsidianCliExecutor cliExecutor;
    private final MCPErrorClassifier errorClassifier;

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
        if (result == null) {
            return MCPResponse.error("obsidian_replace", "EXECUTION_ERROR", "Obsidian 替换执行器未返回结果");
        }
        if (!result.isSuccess()) {
            return errorClassifier.classifyCliFailure("obsidian_replace", result.stderr());
        }
        MCPResponse response = MCPResponse.success("obsidian_replace", result.stdout());
        response.setFallbackUsed(result.stdout().contains("[fallback]"));
        return response;
    }
}
