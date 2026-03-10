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
 * Obsidian MCP 工具 — 创建笔记
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_create",
        name = "创建 Obsidian 笔记",
        description = "在 Obsidian 笔记库中创建新笔记，可指定目录、初始内容和模板。",
        useWhen = "当用户明确要新建笔记、知识卡片、草稿或日记时使用。",
        avoidWhen = "不要用于更新已有笔记、替换局部文本、删除笔记或按关键词搜索笔记。",
        examples = {"创建一个关于 Docker 的笔记", "在 3-Knowledge 下新建 Spring 笔记", "用日记模板创建今天的笔记"},
        sceneKeywords = {"Obsidian", "笔记创建", "知识沉淀"},
        requireUserId = true,
        operationType = MCPTool.OperationType.WRITE,
        confirmationRequired = true,
        timeoutSeconds = 15,
        maxRetries = 0,
        sensitivity = MCPTool.Sensitivity.HIGH,
        sensitiveParams = {"content"},
        fallbackMessage = "Obsidian 写入暂时不可用，本次不会执行创建。",
        parameters = {
                @MCPParam(name = "name", description = "笔记名称（不含 .md 后缀）", type = "string",
                        required = true, example = "AI 工具调用设计"),
                @MCPParam(name = "path", description = "目标文件夹路径", type = "string", required = false,
                        example = "3-Knowledge/AI"),
                @MCPParam(name = "content", description = "笔记初始内容（Markdown 格式）", type = "string", required = false,
                        example = "# AI 工具调用设计"),
                @MCPParam(name = "template", description = "使用的模板名称", type = "string", required = false,
                        example = "daily-note")
        }
)
public class ObsidianCreateNoteTool {

    private final ObsidianCliExecutor cliExecutor;
    private final MCPErrorClassifier errorClassifier;

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
        if (result == null) {
            return MCPResponse.error("obsidian_create", "EXECUTION_ERROR", "Obsidian 创建执行器未返回结果");
        }
        if (!result.isSuccess()) {
            return errorClassifier.classifyCliFailure("obsidian_create", result.stderr());
        }
        MCPResponse response = MCPResponse.success("obsidian_create", "笔记「" + name + "」创建成功。\n" + result.stdout());
        response.setFallbackUsed(result.stdout().contains("[fallback]"));
        return response;
    }
}
