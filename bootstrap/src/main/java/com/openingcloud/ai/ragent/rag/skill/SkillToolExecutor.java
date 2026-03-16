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
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequestSource;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPService;
import com.openingcloud.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Skill 工具执行器（Skill-Based RAG Level 2）
 * <p>
 * 负责执行 AI 请求的工具调用，路由到对应的后端基础设施：
 * - search_kb → 调用多通道检索引擎（Milvus 向量搜索 + 后处理链）
 * - search_all → 跨知识库全局搜索
 * - get_document_detail → 查询文档原文
 * - 其他 → MCPService 执行
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillToolExecutor {

    private static final int DEFAULT_TOP_K = 5;

    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final MCPService mcpService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 执行工具调用，返回结果文本
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @param token    取消令牌
     * @return 工具执行结果
     */
    public ToolExecutionResult execute(String toolName, Map<String, Object> args, CancellationToken token) {
        if (StrUtil.isBlank(toolName)) {
            return ToolExecutionResult.error("工具名称为空");
        }

        log.info("执行 Skill 工具: tool={}, args={}", toolName, args);
        try {
            return switch (toolName) {
                case "search_kb" -> executeSearchKb(args, token);
                case "search_all" -> executeSearchAll(args, token);
                case "get_document_detail" -> executeGetDocumentDetail(args);
                default -> executeMcpTool(toolName, args, token);
            };
        } catch (Exception e) {
            log.error("工具执行失败: tool={}", toolName, e);
            return ToolExecutionResult.error("工具执行失败: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeSearchKb(Map<String, Object> args, CancellationToken token) {
        String kbId = getStringArg(args, "kb_id");
        String query = getStringArg(args, "query");
        int topK = getIntArg(args, "top_k", DEFAULT_TOP_K);

        if (StrUtil.isBlank(query)) {
            return ToolExecutionResult.error("search_kb 缺少必填参数: query");
        }
        if (StrUtil.isBlank(kbId)) {
            return ToolExecutionResult.error("search_kb 缺少必填参数: kb_id");
        }

        // 构造单子问题意图，用空 NodeScore 走全局通道
        SubQuestionIntent intent = new SubQuestionIntent(query, List.of());
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
                List.of(intent), topK, token
        );

        // 按 kbId 过滤结果
        List<RetrievedChunk> filtered = chunks.stream()
                .filter(c -> kbId.equals(c.getKbId()))
                .toList();

        return ToolExecutionResult.success(formatChunks(filtered, query));
    }

    private ToolExecutionResult executeSearchAll(Map<String, Object> args, CancellationToken token) {
        String query = getStringArg(args, "query");
        int topK = getIntArg(args, "top_k", DEFAULT_TOP_K);

        if (StrUtil.isBlank(query)) {
            return ToolExecutionResult.error("search_all 缺少必填参数: query");
        }

        SubQuestionIntent intent = new SubQuestionIntent(query, List.of());
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
                List.of(intent), topK, token
        );

        return ToolExecutionResult.success(formatChunks(chunks, query));
    }

    private ToolExecutionResult executeGetDocumentDetail(Map<String, Object> args) {
        String documentId = getStringArg(args, "document_id");
        if (StrUtil.isBlank(documentId)) {
            return ToolExecutionResult.error("get_document_detail 缺少必填参数: document_id");
        }

        try {
            KnowledgeDocumentDO doc = knowledgeDocumentMapper.selectById(Long.valueOf(documentId));
            if (doc == null) {
                return ToolExecutionResult.error("文档不存在: " + documentId);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("文档名称: ").append(doc.getDocName()).append("\n");
            sb.append("文件类型: ").append(StrUtil.blankToDefault(doc.getFileType(), "未知")).append("\n");
            sb.append("来源: ").append(StrUtil.blankToDefault(doc.getSourceLocation(), "本地上传")).append("\n");
            sb.append("分块数: ").append(doc.getChunkCount() != null ? doc.getChunkCount() : "未知").append("\n");
            sb.append("（原文未存储于数据库，请使用 search_kb 搜索该文档的相关片段获取内容）");
            return ToolExecutionResult.success(sb.toString());
        } catch (NumberFormatException e) {
            return ToolExecutionResult.error("无效的文档 ID: " + documentId);
        }
    }

    private ToolExecutionResult executeMcpTool(String toolId, Map<String, Object> args, CancellationToken token) {
        if (!mcpService.isToolAvailable(toolId)) {
            return ToolExecutionResult.error("未知工具: " + toolId);
        }

        MCPRequest request = MCPRequest.builder()
                .toolId(toolId)
                .parameters(args)
                .requestSource(MCPRequestSource.RETRIEVAL)
                .build();

        MCPResponse response = mcpService.execute(request);
        if (response.isSuccess()) {
            return ToolExecutionResult.success(
                    StrUtil.blankToDefault(response.getTextResult(), "工具执行成功，无文本结果。")
            );
        } else {
            return ToolExecutionResult.error(
                    "工具执行失败: " + StrUtil.blankToDefault(response.getErrorMessage(), "未知错误")
            );
        }
    }

    private String formatChunks(List<RetrievedChunk> chunks, String query) {
        if (CollUtil.isEmpty(chunks)) {
            return "未找到与「" + query + "」相关的内容。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(chunks.size()).append(" 条相关结果：\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("--- 结果 ").append(i + 1).append(" ---\n");
            if (StrUtil.isNotBlank(chunk.getDocumentId())) {
                sb.append("[文档ID: ").append(chunk.getDocumentId()).append("]\n");
            }
            if (StrUtil.isNotBlank(chunk.getKbId())) {
                sb.append("[知识库ID: ").append(chunk.getKbId()).append("]\n");
            }
            if (chunk.getScore() != null) {
                sb.append("[相关度: ").append(String.format("%.4f", chunk.getScore())).append("]\n");
            }
            sb.append(chunk.getText()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String getStringArg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object val = args.get(key);
        return val == null ? null : val.toString();
    }

    private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object val = args.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 工具执行结果
     */
    public record ToolExecutionResult(boolean success, String content) {

        public static ToolExecutionResult success(String content) {
            return new ToolExecutionResult(true, content);
        }

        public static ToolExecutionResult error(String message) {
            return new ToolExecutionResult(false, message);
        }
    }
}
