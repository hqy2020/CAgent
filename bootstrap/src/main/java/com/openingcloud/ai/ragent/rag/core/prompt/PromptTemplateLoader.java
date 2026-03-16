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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.rag.dao.entity.PromptTemplateDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.PromptTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示模板加载器
 * 支持 DB 优先加载（启用的模板从数据库读取），回退到类路径文件。
 * 通过版本号检测 DB 更新，避免每次查询。
 */
@Slf4j
@Service
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, Integer> versionCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private PromptTemplateMapper promptTemplateMapper;

    /**
     * 文件路径到 promptKey 的映射，用于 DB 查找
     */
    private static final Map<String, String> PATH_TO_KEY = Map.ofEntries(
            Map.entry("prompt/answer-chat-system.st", "chat_system"),
            Map.entry("prompt/answer-chat-kb.st", "answer_kb"),
            Map.entry("prompt/answer-chat-code.st", "answer_code"),
            Map.entry("prompt/answer-chat-mcp.st", "answer_mcp"),
            Map.entry("prompt/answer-chat-mcp-kb-mixed.st", "answer_mcp_kb_mixed"),
            Map.entry("prompt/progressive/core.st", "progressive_core"),
            Map.entry("prompt/progressive/scene-kb.st", "progressive_scene_kb"),
            Map.entry("prompt/progressive/scene-mcp.st", "progressive_scene_mcp"),
            Map.entry("prompt/progressive/scene-mixed.st", "progressive_scene_mixed"),
            Map.entry("prompt/progressive/optional-multi-question.st", "progressive_multi_question"),
            Map.entry("prompt/progressive/optional-links-and-media.st", "progressive_links_media"),
            Map.entry("prompt/progressive/optional-detailed-mode.st", "progressive_detailed"),
            Map.entry("prompt/intent-classifier.st", "intent_classifier"),
            Map.entry("prompt/guidance-prompt.st", "guidance"),
            Map.entry("prompt/conversation-title.st", "conversation_title"),
            Map.entry("prompt/conversation-summary.st", "conversation_summary"),
            Map.entry("prompt/user-question-rewrite.st", "query_rewrite"),
            Map.entry("prompt/mcp-parameter-extract.st", "mcp_param_extract"),
            Map.entry("prompt/mcp-parameter-repair.st", "mcp_param_repair"),
            Map.entry("prompt/pdf-format-guard.st", "pdf_format_guard"),
            Map.entry("prompt/eval-faithfulness.st", "eval_faithfulness"),
            Map.entry("prompt/eval-relevancy.st", "eval_relevancy"),
            Map.entry("prompt/eval-correctness.st", "eval_correctness"),
            Map.entry("prompt/memory/extract-insights.st", "memory_extract_insights"),
            Map.entry("prompt/memory/generate-digest.st", "memory_generate_digest"),
            Map.entry("prompt/memory/reconcile.st", "memory_reconcile"),
            Map.entry("prompt/memory/update-profile.st", "memory_update_profile"));

    public PromptTemplateLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 加载指定路径的提示模板
     * <p>
     * 优先从数据库加载已启用的模板，若 DB 无记录或未启用则回退到文件。
     *
     * @param path 模板文件路径，支持classpath:前缀
     * @return 模板内容字符串
     * @throws IllegalArgumentException 当路径为空时抛出
     * @throws IllegalStateException    当模板文件不存在或读取失败时抛出
     */
    public String load(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("提示模板路径为空");
        }
        // Try DB first
        String promptKey = PATH_TO_KEY.get(path);
        if (promptKey != null && promptTemplateMapper != null) {
            try {
                PromptTemplateDO dbTemplate =
                        promptTemplateMapper.selectOne(new LambdaQueryWrapper<PromptTemplateDO>()
                                .eq(PromptTemplateDO::getPromptKey, promptKey)
                                .eq(PromptTemplateDO::getEnabled, 1)
                                .eq(PromptTemplateDO::getDeleted, 0));
                if (dbTemplate != null) {
                    Integer cachedVersion = versionCache.get(promptKey);
                    if (cachedVersion == null || !cachedVersion.equals(dbTemplate.getVersion())) {
                        cache.put(path, dbTemplate.getContent());
                        versionCache.put(promptKey, dbTemplate.getVersion());
                    }
                    String cached = cache.get(path);
                    if (cached != null) {
                        return cached;
                    }
                }
            } catch (Exception e) {
                log.debug("DB 加载提示词失败，回退到文件: key={}, error={}", promptKey, e.getMessage());
            }
        }
        // Fallback to file
        return cache.computeIfAbsent(path, this::readResource);
    }

    /**
     * 渲染提示模板，将模板中的占位符替换为实际值
     *
     * @param path  模板文件路径
     * @param slots 占位符映射表，键为占位符名称，值为替换内容
     * @return 渲染后的完整提示文本
     */
    public String render(String path, Map<String, String> slots) {
        String template = load(path);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 根据 promptKey 失效缓存，使下次 load 重新从 DB 或文件读取
     *
     * @param promptKey 提示词标识键
     */
    public void invalidateCache(String promptKey) {
        PATH_TO_KEY.entrySet().stream()
                .filter(e -> e.getValue().equals(promptKey))
                .map(Map.Entry::getKey)
                .forEach(path -> {
                    cache.remove(path);
                    versionCache.remove(promptKey);
                });
    }

    /**
     * 从资源路径读取模板内容
     *
     * @param path 模板文件路径
     * @return 模板内容字符串
     * @throws IllegalStateException 当模板文件不存在或读取失败时抛出
     */
    private String readResource(String path) {
        String location = path.startsWith("classpath:") ? path : "classpath:" + path;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词模板路径不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取提示模板失败，路径：{}", path, e);
            throw new IllegalStateException("读取提示模板失败，路径：" + path, e);
        }
    }
}
