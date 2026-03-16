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

package com.openingcloud.ai.ragent.rag.skill;

import cn.hutool.core.collection.CollUtil;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Skill 工具注册中心（Skill-Based RAG Level 2）
 * <p>
 * 负责管理所有可用工具的定义并生成工具描述文本注入 system prompt。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillToolRegistry {

    private final MCPService mcpService;

    /**
     * 生成工具描述文本（注入 system prompt）
     *
     * @return 所有可用工具的描述文本
     */
    public String buildToolsPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");

        // 核心 KB 工具
        sb.append("### 1. search_kb\n");
        sb.append("在指定知识库中搜索相关内容。\n");
        sb.append("参数：\n");
        sb.append("- kb_id (string, 必填): 知识库 ID，从上方知识库目录中选择\n");
        sb.append("- query (string, 必填): 搜索查询语句\n");
        sb.append("- top_k (int, 可选, 默认 5): 返回结果数量\n\n");

        sb.append("### 2. search_all\n");
        sb.append("跨所有知识库全局搜索。当不确定该搜哪个知识库时使用。\n");
        sb.append("参数：\n");
        sb.append("- query (string, 必填): 搜索查询语句\n");
        sb.append("- top_k (int, 可选, 默认 5): 返回结果数量\n\n");

        sb.append("### 3. get_document_detail\n");
        sb.append("获取指定文档的完整内容。当搜索结果片段不够详细时使用。\n");
        sb.append("参数：\n");
        sb.append("- document_id (string, 必填): 文档 ID（从搜索结果中获取）\n\n");

        // 动态 MCP 工具
        List<MCPTool> mcpTools = mcpService.listAvailableTools();
        if (CollUtil.isNotEmpty(mcpTools)) {
            int toolIndex = 4;
            for (MCPTool tool : mcpTools) {
                if (tool.getOperationType() == MCPTool.OperationType.WRITE) {
                    continue;
                }
                if (!tool.isVisibleToModel()) {
                    continue;
                }
                sb.append("### ").append(toolIndex).append(". ").append(tool.getToolId()).append("\n");
                sb.append(tool.getDescription()).append("\n");
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    sb.append("参数：\n");
                    tool.getParameters().forEach((name, def) ->
                            sb.append("- ").append(name)
                                    .append(" (").append(def.getType())
                                    .append(def.isRequired() ? ", 必填" : ", 可选")
                                    .append("): ").append(def.getDescription())
                                    .append("\n")
                    );
                }
                sb.append("\n");
                toolIndex++;
            }
        }

        return sb.toString().trim();
    }
}
