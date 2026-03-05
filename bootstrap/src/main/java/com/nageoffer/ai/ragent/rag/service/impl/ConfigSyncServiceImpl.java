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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.AIModelCandidateDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AIModelProviderDO;
import com.nageoffer.ai.ragent.rag.dao.entity.SystemConfigDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AIModelCandidateMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AIModelProviderMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.SystemConfigMapper;
import com.nageoffer.ai.ragent.rag.service.ConfigSyncService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配置同步服务实现
 * <p>
 * 将 DB 持久化的配置同步到 Spring 单例 Bean，实现运行时热更新。
 * 核心思路：直接调用 @Data 生成的 setter 修改 Bean 字段值，
 * 所有消费方通过 getter 实时读取，无需重启服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigSyncServiceImpl implements ConfigSyncService {

    private final AIModelProviderMapper providerMapper;
    private final AIModelCandidateMapper candidateMapper;
    private final SystemConfigMapper configMapper;
    private final AIModelProperties aiModelProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties rateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initSync() {
        try {
            Long providerCount = providerMapper.selectCount(Wrappers.emptyWrapper());
            Long candidateCount = candidateMapper.selectCount(Wrappers.emptyWrapper());
            if (providerCount > 0 || candidateCount > 0) {
                syncAIModelProperties();
                log.info("从 DB 同步 AI 模型配置完成：providers={}, candidates={}", providerCount, candidateCount);
            }

            Long configCount = configMapper.selectCount(Wrappers.emptyWrapper());
            if (configCount > 0) {
                syncSystemConfig();
                log.info("从 DB 同步系统配置完成：configs={}", configCount);
            }
        } catch (Exception e) {
            log.warn("启动时配置同步失败，将使用 YAML 默认值: {}", e.getMessage());
        }
    }

    @Override
    public void syncAIModelProperties() {
        // 同步 Provider
        List<AIModelProviderDO> providers = providerMapper.selectList(
                Wrappers.<AIModelProviderDO>lambdaQuery().eq(AIModelProviderDO::getEnabled, 1));
        Map<String, AIModelProperties.ProviderConfig> providerMap = new HashMap<>();
        for (AIModelProviderDO p : providers) {
            AIModelProperties.ProviderConfig config = new AIModelProperties.ProviderConfig();
            config.setUrl(p.getBaseUrl());
            config.setApiKey(p.getApiKey());
            config.setEndpoints(parseEndpoints(p.getEndpoints()));
            providerMap.put(p.getProviderKey(), config);
        }
        aiModelProperties.setProviders(new HashMap<>(providerMap));

        // 同步 Candidate（按类型分组）
        List<AIModelCandidateDO> allCandidates = candidateMapper.selectList(Wrappers.emptyWrapper());
        Map<String, List<AIModelCandidateDO>> byType = allCandidates.stream()
                .collect(Collectors.groupingBy(AIModelCandidateDO::getModelType));

        syncModelGroup(aiModelProperties.getChat(), byType.getOrDefault("chat", List.of()));
        syncModelGroup(aiModelProperties.getEmbedding(), byType.getOrDefault("embedding", List.of()));
        syncModelGroup(aiModelProperties.getRerank(), byType.getOrDefault("rerank", List.of()));
    }

    private void syncModelGroup(AIModelProperties.ModelGroup group, List<AIModelCandidateDO> candidates) {
        List<AIModelProperties.ModelCandidate> list = new ArrayList<>();
        String defaultModel = null;
        String deepThinkingModel = null;

        for (AIModelCandidateDO c : candidates) {
            AIModelProperties.ModelCandidate mc = new AIModelProperties.ModelCandidate();
            mc.setId(c.getModelId());
            mc.setProvider(c.getProviderKey());
            mc.setModel(c.getModelName());
            mc.setUrl(c.getCustomUrl());
            mc.setDimension(c.getDimension());
            mc.setPriority(c.getPriority());
            mc.setEnabled(c.getEnabled() != null && c.getEnabled() == 1);
            mc.setSupportsThinking(c.getSupportsThinking() != null && c.getSupportsThinking() == 1);
            list.add(mc);

            if (c.getIsDefault() != null && c.getIsDefault() == 1) {
                defaultModel = c.getModelId();
            }
            if (c.getIsDeepThinking() != null && c.getIsDeepThinking() == 1) {
                deepThinkingModel = c.getModelId();
            }
        }

        // 整体替换引用，避免 ConcurrentModificationException
        group.setCandidates(new ArrayList<>(list));
        if (defaultModel != null) {
            group.setDefaultModel(defaultModel);
        }
        if (deepThinkingModel != null) {
            group.setDeepThinkingModel(deepThinkingModel);
        }
    }

    @Override
    public void syncSystemConfig() {
        List<SystemConfigDO> configs = configMapper.selectList(Wrappers.emptyWrapper());
        Map<String, Map<String, String>> grouped = configs.stream()
                .collect(Collectors.groupingBy(
                        SystemConfigDO::getConfigGroup,
                        Collectors.toMap(SystemConfigDO::getConfigKey, c -> c.getConfigValue() != null ? c.getConfigValue() : "")));

        applyRagDefault(grouped.getOrDefault("rag.default", Map.of()));
        applyQueryRewrite(grouped.getOrDefault("rag.query-rewrite", Map.of()));
        applyPromptProgressive(grouped.getOrDefault("rag.prompt-progressive", Map.of()));
        applyRateLimit(grouped.getOrDefault("rag.rate-limit", Map.of()));
        applyMemory(grouped.getOrDefault("rag.memory", Map.of()));
        applyAISelection(grouped.getOrDefault("ai.selection", Map.of()));
        applyAIStream(grouped.getOrDefault("ai.stream", Map.of()));
    }

    private void applyRagDefault(Map<String, String> kv) {
        if (kv.containsKey("collectionName")) ragDefaultProperties.setCollectionName(kv.get("collectionName"));
        if (kv.containsKey("dimension")) ragDefaultProperties.setDimension(parseInt(kv.get("dimension")));
        if (kv.containsKey("metricType")) ragDefaultProperties.setMetricType(kv.get("metricType"));
    }

    private void applyQueryRewrite(Map<String, String> kv) {
        if (kv.containsKey("enabled")) ragConfigProperties.setQueryRewriteEnabled(parseBool(kv.get("enabled")));
        if (kv.containsKey("maxHistoryMessages"))
            ragConfigProperties.setQueryRewriteMaxHistoryMessages(parseInt(kv.get("maxHistoryMessages")));
        if (kv.containsKey("maxHistoryChars"))
            ragConfigProperties.setQueryRewriteMaxHistoryChars(parseInt(kv.get("maxHistoryChars")));
    }

    private void applyPromptProgressive(Map<String, String> kv) {
        if (kv.containsKey("enabled"))
            ragConfigProperties.setPromptProgressiveEnabled(parseBool(kv.get("enabled")));
        if (kv.containsKey("coreEnabled"))
            ragConfigProperties.setPromptProgressiveCoreEnabled(parseBool(kv.get("coreEnabled")));
        if (kv.containsKey("optionalMultiQuestionEnabled"))
            ragConfigProperties.setPromptProgressiveOptionalMultiQuestionEnabled(
                    parseBool(kv.get("optionalMultiQuestionEnabled")));
        if (kv.containsKey("optionalLinkMediaEnabled"))
            ragConfigProperties.setPromptProgressiveOptionalLinkMediaEnabled(
                    parseBool(kv.get("optionalLinkMediaEnabled")));
        if (kv.containsKey("optionalDetailedModeEnabled"))
            ragConfigProperties.setPromptProgressiveOptionalDetailedModeEnabled(
                    parseBool(kv.get("optionalDetailedModeEnabled")));
    }

    private void applyRateLimit(Map<String, String> kv) {
        if (kv.containsKey("enabled")) rateLimitProperties.setGlobalEnabled(parseBool(kv.get("enabled")));
        if (kv.containsKey("maxConcurrent"))
            rateLimitProperties.setGlobalMaxConcurrent(parseInt(kv.get("maxConcurrent")));
        if (kv.containsKey("maxWaitSeconds"))
            rateLimitProperties.setGlobalMaxWaitSeconds(parseInt(kv.get("maxWaitSeconds")));
        if (kv.containsKey("leaseSeconds"))
            rateLimitProperties.setGlobalLeaseSeconds(parseInt(kv.get("leaseSeconds")));
        if (kv.containsKey("pollIntervalMs"))
            rateLimitProperties.setGlobalPollIntervalMs(parseInt(kv.get("pollIntervalMs")));
    }

    private void applyMemory(Map<String, String> kv) {
        if (kv.containsKey("historyKeepTurns"))
            memoryProperties.setHistoryKeepTurns(parseInt(kv.get("historyKeepTurns")));
        if (kv.containsKey("summaryStartTurns"))
            memoryProperties.setSummaryStartTurns(parseInt(kv.get("summaryStartTurns")));
        if (kv.containsKey("summaryEnabled"))
            memoryProperties.setSummaryEnabled(parseBool(kv.get("summaryEnabled")));
        if (kv.containsKey("ttlMinutes")) memoryProperties.setTtlMinutes(parseInt(kv.get("ttlMinutes")));
        if (kv.containsKey("summaryMaxChars"))
            memoryProperties.setSummaryMaxChars(parseInt(kv.get("summaryMaxChars")));
        if (kv.containsKey("titleMaxLength"))
            memoryProperties.setTitleMaxLength(parseInt(kv.get("titleMaxLength")));
    }

    private void applyAISelection(Map<String, String> kv) {
        if (kv.containsKey("failureThreshold"))
            aiModelProperties.getSelection().setFailureThreshold(parseInt(kv.get("failureThreshold")));
        if (kv.containsKey("openDurationMs"))
            aiModelProperties.getSelection().setOpenDurationMs(parseLong(kv.get("openDurationMs")));
    }

    private void applyAIStream(Map<String, String> kv) {
        if (kv.containsKey("messageChunkSize"))
            aiModelProperties.getStream().setMessageChunkSize(parseInt(kv.get("messageChunkSize")));
    }

    private Map<String, String> parseEndpoints(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("解析 endpoints JSON 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean parseBool(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
