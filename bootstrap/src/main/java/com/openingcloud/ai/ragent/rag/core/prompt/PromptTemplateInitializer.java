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

package com.openingcloud.ai.ragent.rag.core.prompt;

import com.openingcloud.ai.ragent.rag.dao.entity.PromptTemplateDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.PromptTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 提示词模板初始化器
 * 应用启动时检查 t_prompt_template 表，若为空则插入全部预定义模板。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptTemplateInitializer implements CommandLineRunner {

    private final PromptTemplateMapper promptTemplateMapper;
    private final ResourceLoader resourceLoader;

    private static final List<TemplateDefinition> TEMPLATES = List.of(
            new TemplateDefinition("chat_system", "系统角色定义", "SYSTEM",
                    "prompt/answer-chat-system.st", "主系统提示词，定义AI助手角色", null, null),
            new TemplateDefinition("answer_kb", "KB问答", "SCENE",
                    "prompt/answer-chat-kb.st", "知识库检索场景提示词",
                    "[{\"name\":\"context\",\"desc\":\"检索到的参考文档\"}]", null),
            new TemplateDefinition("answer_code", "代码直答", "SCENE",
                    "prompt/answer-chat-code.st", "代码问题场景提示词", null, null),
            new TemplateDefinition("answer_mcp", "MCP工具", "SCENE",
                    "prompt/answer-chat-mcp.st", "MCP工具调用场景提示词",
                    "[{\"name\":\"context\",\"desc\":\"MCP工具返回结果\"}]", null),
            new TemplateDefinition("answer_mcp_kb_mixed", "混合场景", "SCENE",
                    "prompt/answer-chat-mcp-kb-mixed.st", "MCP+KB混合场景提示词", null, null),
            new TemplateDefinition("progressive_core", "渐进式-核心", "SCENE",
                    "prompt/progressive/core.st", "渐进式提示词核心模块", null, null),
            new TemplateDefinition("progressive_scene_kb", "渐进式-KB", "SCENE",
                    "prompt/progressive/scene-kb.st", "渐进式KB场景模块", null, null),
            new TemplateDefinition("progressive_scene_mcp", "渐进式-MCP", "SCENE",
                    "prompt/progressive/scene-mcp.st", "渐进式MCP场景模块", null, null),
            new TemplateDefinition("progressive_scene_mixed", "渐进式-混合", "SCENE",
                    "prompt/progressive/scene-mixed.st", "渐进式混合场景模块", null, null),
            new TemplateDefinition("progressive_multi_question", "多问题细则", "SCENE",
                    "prompt/progressive/optional-multi-question.st", "多问题拆分细则", null, null),
            new TemplateDefinition("progressive_links_media", "链接媒体约束", "SCENE",
                    "prompt/progressive/optional-links-and-media.st", "链接和媒体内容约束", null, null),
            new TemplateDefinition("progressive_detailed", "详细模式", "SCENE",
                    "prompt/progressive/optional-detailed-mode.st", "详细模式增强", null, null),
            new TemplateDefinition("intent_classifier", "意图分类", "FLOW",
                    "prompt/intent-classifier.st", "意图识别分类提示词",
                    "[{\"name\":\"intent_tree\",\"desc\":\"意图树结构\"}]", null),
            new TemplateDefinition("guidance", "歧义引导", "FLOW",
                    "prompt/guidance-prompt.st", "歧义问题引导提示词", null, null),
            new TemplateDefinition("conversation_title", "会话标题", "FLOW",
                    "prompt/conversation-title.st", "自动生成会话标题",
                    "[{\"name\":\"message\",\"desc\":\"用户消息\"}]", null),
            new TemplateDefinition("conversation_summary", "对话摘要", "FLOW",
                    "prompt/conversation-summary.st", "对话记忆摘要生成", null, null),
            new TemplateDefinition("query_rewrite", "查询改写", "FLOW",
                    "prompt/user-question-rewrite.st", "用户查询改写", null, null),
            new TemplateDefinition("mcp_param_extract", "MCP参数提取", "FLOW",
                    "prompt/mcp-parameter-extract.st", "MCP工具参数提取", null, null),
            new TemplateDefinition("mcp_param_repair", "MCP参数纠偏", "FLOW",
                    "prompt/mcp-parameter-repair.st", "MCP工具参数校正", null, null),
            new TemplateDefinition("pdf_format_guard", "PDF格式守护", "FLOW",
                    "prompt/pdf-format-guard.st", "PDF格式输出保护", null, null),
            new TemplateDefinition("eval_faithfulness", "忠实度评分", "EVAL",
                    "prompt/eval-faithfulness.st", "RAG评测-忠实度评分",
                    "[{\"name\":\"context\",\"desc\":\"参考文档\"},{\"name\":\"answer\",\"desc\":\"系统回答\"}]",
                    null),
            new TemplateDefinition("eval_relevancy", "相关性评分", "EVAL",
                    "prompt/eval-relevancy.st", "RAG评测-答案相关性评分",
                    "[{\"name\":\"query\",\"desc\":\"用户问题\"},{\"name\":\"answer\",\"desc\":\"系统回答\"}]",
                    null),
            new TemplateDefinition("eval_correctness", "正确率评分", "EVAL",
                    "prompt/eval-correctness.st", "RAG评测-正确率评分",
                    "[{\"name\":\"query\",\"desc\":\"用户问题\"},{\"name\":\"expected_answer\",\"desc\":\"参考答案\"},{\"name\":\"actual_answer\",\"desc\":\"系统回答\"}]",
                    null));
    private static final List<TemplateDefinition> MEMORY_TEMPLATES = List.of(
            new TemplateDefinition("memory_extract_insights", "记忆提取-Insights", "MEMORY",
                    "prompt/memory/extract-insights.st", "从对话中提取用户原子事实",
                    "[{\"name\":\"max_insights\",\"desc\":\"最大提取条数\"},{\"name\":\"conversation\",\"desc\":\"对话历史\"}]",
                    null),
            new TemplateDefinition("memory_generate_digest", "记忆提取-Digest", "MEMORY",
                    "prompt/memory/generate-digest.st", "生成会话摘要",
                    "[{\"name\":\"max_chars\",\"desc\":\"最大字符数\"},{\"name\":\"conversation\",\"desc\":\"对话历史\"}]",
                    null),
            new TemplateDefinition("memory_reconcile", "记忆调和", "MEMORY",
                    "prompt/memory/reconcile.st", "判断新旧记忆关系",
                    "[{\"name\":\"new_memory\",\"desc\":\"新记忆\"},{\"name\":\"existing_memories\",\"desc\":\"已有相似记忆\"}]",
                    null),
            new TemplateDefinition("memory_update_profile", "画像更新", "MEMORY",
                    "prompt/memory/update-profile.st", "根据insights更新用户画像",
                    "[{\"name\":\"current_profile\",\"desc\":\"当前画像JSON\"},{\"name\":\"new_insights\",\"desc\":\"新提取的信息\"},{\"name\":\"max_summary_chars\",\"desc\":\"摘要最大字符数\"}]",
                    null));

    @Override
    public void run(String... args) {
        try {
            Long count = promptTemplateMapper.selectCount(null);
            if (count != null && count > 0) {
                log.info("提示词模板表已有 {} 条数据，跳过初始化", count);
                return;
            }
        } catch (Exception e) {
            log.warn("检查提示词模板表失败，跳过初始化: {}", e.getMessage());
            return;
        }

        log.info("开始初始化提示词模板...");
        int success = 0;
        List<TemplateDefinition> allTemplates = new java.util.ArrayList<>(TEMPLATES);
        allTemplates.addAll(MEMORY_TEMPLATES);
        for (TemplateDefinition def : allTemplates) {
            try {
                String content = loadFileContent(def.filePath);
                PromptTemplateDO template = PromptTemplateDO.builder()
                        .promptKey(def.promptKey)
                        .name(def.name)
                        .category(def.category)
                        .content(content != null ? content : def.defaultContent)
                        .filePath(def.filePath)
                        .variables(def.variables)
                        .description(def.description)
                        .version(1)
                        .enabled(0)
                        .build();
                promptTemplateMapper.insert(template);
                success++;
            } catch (Exception e) {
                log.warn("初始化提示词模板失败: key={}", def.promptKey, e);
            }
        }
        log.info("提示词模板初始化完成，成功 {}/{}", success, allTemplates.size());
    }

    private String loadFileContent(String filePath) {
        if (filePath == null) {
            return null;
        }
        try {
            String location = "classpath:" + filePath;
            Resource resource = resourceLoader.getResource(location);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.debug("读取模板文件失败: {}", filePath);
        }
        return null;
    }

    private record TemplateDefinition(
            String promptKey,
            String name,
            String category,
            String filePath,
            String description,
            String variables,
            String defaultContent) {}
}
