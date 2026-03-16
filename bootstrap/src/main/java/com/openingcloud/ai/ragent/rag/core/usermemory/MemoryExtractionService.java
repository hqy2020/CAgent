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

package com.openingcloud.ai.ragent.rag.core.usermemory;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.core.usermemory.model.MemoryType;
import com.openingcloud.ai.ragent.rag.dao.entity.UserMemoryDO;
import com.openingcloud.ai.ragent.rag.dao.entity.UserProfileDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.MEMORY_EXTRACT_INSIGHTS_PROMPT_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.MEMORY_GENERATE_DIGEST_PROMPT_PATH;
import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.MEMORY_UPDATE_PROFILE_PROMPT_PATH;

/**
 * 会话结束后异步提取 insights + digest，并更新用户画像
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final UserMemoryService userMemoryService;
    private final UserProfileService userProfileService;
    private final MemoryReconcileService reconcileService;
    private final MemoryVectorStoreService vectorStoreService;
    private final UserMemoryProperties properties;

    /**
     * 提取 insights 和 digest，并更新画像
     *
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @param chatHistory    完整对话历史文本
     */
    public void extractAndDigest(Long userId, String conversationId, String chatHistory) {
        if (StrUtil.isBlank(chatHistory)) {
            return;
        }

        try {
            // 1. 提取 insights
            List<String> insights = extractInsights(chatHistory);
            log.info("提取到 {} 条 insights, conversationId={}", insights.size(), conversationId);

            // 2. 调和并保存 insights
            for (String insight : insights) {
                UserMemoryDO saved = reconcileService.reconcileAndSave(
                        userId, MemoryType.INSIGHT, insight, conversationId, null);
                if (saved != null) {
                    vectorStoreService.upsert(saved.getId(), userId, saved.getContent());
                }
            }

            // 3. 生成 digest
            String digest = generateDigest(chatHistory);
            if (StrUtil.isNotBlank(digest)) {
                UserMemoryDO saved = userMemoryService.save(
                        userId, MemoryType.DIGEST, digest, conversationId, null);
                vectorStoreService.upsert(saved.getId(), userId, saved.getContent());
            }

            // 4. 更新画像
            updateProfile(userId, insights);
        } catch (Exception e) {
            log.error("记忆提取与画像更新失败: userId={}, conversationId={}", userId, conversationId, e);
        }
    }

    private List<String> extractInsights(String chatHistory) {
        String prompt = promptTemplateLoader.render(MEMORY_EXTRACT_INSIGHTS_PROMPT_PATH,
                Map.of("max_insights", String.valueOf(properties.getMaxInsightsPerSession()),
                        "conversation", chatHistory));

        String response = llmService.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.3)
                .maxTokens(2000)
                .build());

        List<String> insights = new ArrayList<>();
        if (StrUtil.isNotBlank(response)) {
            for (String line : response.split("\\R")) {
                String trimmed = line.trim();
                // 去掉序号前缀
                trimmed = trimmed.replaceFirst("^\\d+[.、)）]\\s*", "");
                if (!trimmed.isEmpty() && trimmed.length() > 3) {
                    insights.add(trimmed);
                }
            }
        }
        return insights;
    }

    private String generateDigest(String chatHistory) {
        String prompt = promptTemplateLoader.render(MEMORY_GENERATE_DIGEST_PROMPT_PATH,
                Map.of("max_chars", String.valueOf(properties.getDigestMaxChars()),
                        "conversation", chatHistory));

        return llmService.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.3)
                .maxTokens(1000)
                .build());
    }

    private void updateProfile(Long userId, List<String> insights) {
        if (insights.isEmpty()) {
            return;
        }

        UserProfileDO profile = userProfileService.loadOrCreate(userId);
        String insightsText = String.join("\n", insights);
        String currentProfileJson = JSONUtil.toJsonStr(Map.of(
                "display_name", StrUtil.emptyIfNull(profile.getDisplayName()),
                "occupation", StrUtil.emptyIfNull(profile.getOccupation()),
                "interests", StrUtil.emptyIfNull(profile.getInterests()),
                "preferences", StrUtil.emptyIfNull(profile.getPreferences()),
                "facts", StrUtil.emptyIfNull(profile.getFacts()),
                "summary", StrUtil.emptyIfNull(profile.getSummary())
        ));

        String prompt = promptTemplateLoader.render(MEMORY_UPDATE_PROFILE_PROMPT_PATH,
                Map.of("current_profile", currentProfileJson,
                        "new_insights", insightsText,
                        "max_summary_chars", String.valueOf(properties.getProfileSummaryMaxChars())));

        String response = llmService.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.3)
                .maxTokens(2000)
                .build());

        if (StrUtil.isBlank(response)) {
            return;
        }

        try {
            // 提取 JSON（可能被 markdown 代码块包裹）
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").trim();
            }

            JSONObject updatedProfile = JSONUtil.parseObj(json);
            if (updatedProfile.containsKey("display_name")) {
                profile.setDisplayName(updatedProfile.getStr("display_name"));
            }
            if (updatedProfile.containsKey("occupation")) {
                profile.setOccupation(updatedProfile.getStr("occupation"));
            }
            if (updatedProfile.containsKey("interests")) {
                Object interests = updatedProfile.get("interests");
                profile.setInterests(interests instanceof JSONArray
                        ? interests.toString() : updatedProfile.getStr("interests"));
            }
            if (updatedProfile.containsKey("preferences")) {
                Object prefs = updatedProfile.get("preferences");
                profile.setPreferences(prefs instanceof JSONObject
                        ? prefs.toString() : updatedProfile.getStr("preferences"));
            }
            if (updatedProfile.containsKey("facts")) {
                Object facts = updatedProfile.get("facts");
                profile.setFacts(facts instanceof JSONArray
                        ? facts.toString() : updatedProfile.getStr("facts"));
            }
            if (updatedProfile.containsKey("summary")) {
                profile.setSummary(updatedProfile.getStr("summary"));
            }

            userProfileService.update(profile);
            log.info("用户画像已更新: userId={}, version={}", userId, profile.getVersion());
        } catch (Exception e) {
            log.warn("解析画像更新结果失败: userId={}", userId, e);
        }
    }
}
