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

package com.openingcloud.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.openingcloud.ai.ragent.infra.config.AIModelProperties;
import com.openingcloud.ai.ragent.rag.config.MemoryProperties;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.config.RAGDefaultProperties;
import com.openingcloud.ai.ragent.rag.config.RAGRateLimitProperties;
import com.openingcloud.ai.ragent.rag.controller.vo.SystemConfigGroupVO;
import com.openingcloud.ai.ragent.rag.controller.vo.SystemConfigGroupVO.ConfigItem;
import com.openingcloud.ai.ragent.rag.dao.entity.SystemConfigDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.SystemConfigMapper;
import com.openingcloud.ai.ragent.rag.service.ConfigSyncService;
import com.openingcloud.ai.ragent.rag.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统配置管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigMapper configMapper;
    private final ConfigSyncService configSyncService;
    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties rateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    private static final Map<String, String> GROUP_LABELS = new LinkedHashMap<>() {{
        put("rag.default", "RAG 默认配置");
        put("rag.query-rewrite", "查询改写");
        put("rag.prompt-progressive", "渐进式披露");
        put("rag.rate-limit", "全局限流");
        put("rag.memory", "记忆管理");
        put("ai.selection", "模型选择策略");
        put("ai.stream", "流式响应");
    }};

    @Override
    public List<SystemConfigGroupVO> listConfigGroups() {
        // 从 DB 获取已有配置
        List<SystemConfigDO> dbConfigs = configMapper.selectList(Wrappers.emptyWrapper());
        Map<String, Map<String, SystemConfigDO>> dbGrouped = dbConfigs.stream()
                .collect(Collectors.groupingBy(SystemConfigDO::getConfigGroup,
                        Collectors.toMap(SystemConfigDO::getConfigKey, c -> c)));

        List<SystemConfigGroupVO> result = new ArrayList<>();

        for (Map.Entry<String, String> entry : GROUP_LABELS.entrySet()) {
            String group = entry.getKey();
            String label = entry.getValue();
            Map<String, SystemConfigDO> dbKV = dbGrouped.getOrDefault(group, Map.of());
            List<ConfigItem> items = buildGroupItems(group, dbKV);

            result.add(SystemConfigGroupVO.builder()
                    .group(group)
                    .groupLabel(label)
                    .items(items)
                    .build());
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfigGroup(String group, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            SystemConfigDO existing = configMapper.selectOne(
                    Wrappers.<SystemConfigDO>lambdaQuery()
                            .eq(SystemConfigDO::getConfigGroup, group)
                            .eq(SystemConfigDO::getConfigKey, key));

            if (existing != null) {
                existing.setConfigValue(value);
                configMapper.updateById(existing);
            } else {
                SystemConfigDO newConfig = SystemConfigDO.builder()
                        .configGroup(group)
                        .configKey(key)
                        .configValue(value)
                        .valueType(inferValueType(value))
                        .build();
                configMapper.insert(newConfig);
            }
        }

        configSyncService.syncSystemConfig();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initFromYaml() {
        Long count = configMapper.selectCount(Wrappers.emptyWrapper());
        if (count > 0) {
            log.info("系统配置表已有数据（{}条），跳过初始化", count);
            return;
        }

        List<SystemConfigDO> configs = new ArrayList<>();

        // rag.default
        addConfig(configs, "rag.default", "collectionName",
                str(ragDefaultProperties.getCollectionName()), "STRING", "默认集合名称");
        addConfig(configs, "rag.default", "dimension",
                str(ragDefaultProperties.getDimension()), "INTEGER", "向量维度");
        addConfig(configs, "rag.default", "metricType",
                str(ragDefaultProperties.getMetricType()), "STRING", "度量类型");

        // rag.query-rewrite
        addConfig(configs, "rag.query-rewrite", "enabled",
                str(ragConfigProperties.getQueryRewriteEnabled()), "BOOLEAN", "查询重写开关");
        addConfig(configs, "rag.query-rewrite", "maxHistoryMessages",
                str(ragConfigProperties.getQueryRewriteMaxHistoryMessages()), "INTEGER", "改写最大历史消息数");
        addConfig(configs, "rag.query-rewrite", "maxHistoryTokens",
                str(ragConfigProperties.getQueryRewriteMaxHistoryTokens()), "INTEGER", "改写最大历史Token数");
        addConfig(configs, "rag.query-rewrite", "maxHistoryChars",
                str(ragConfigProperties.getQueryRewriteMaxHistoryChars()), "INTEGER", "改写最大历史字符数");

        // rag.prompt-progressive
        addConfig(configs, "rag.prompt-progressive", "enabled",
                str(ragConfigProperties.getPromptProgressiveEnabled()), "BOOLEAN", "渐进式披露总开关");
        addConfig(configs, "rag.prompt-progressive", "coreEnabled",
                str(ragConfigProperties.getPromptProgressiveCoreEnabled()), "BOOLEAN", "核心规则层开关");
        addConfig(configs, "rag.prompt-progressive", "optionalMultiQuestionEnabled",
                str(ragConfigProperties.getPromptProgressiveOptionalMultiQuestionEnabled()), "BOOLEAN", "多子问题规则开关");
        addConfig(configs, "rag.prompt-progressive", "optionalLinkMediaEnabled",
                str(ragConfigProperties.getPromptProgressiveOptionalLinkMediaEnabled()), "BOOLEAN", "链接/图片规则开关");
        addConfig(configs, "rag.prompt-progressive", "optionalDetailedModeEnabled",
                str(ragConfigProperties.getPromptProgressiveOptionalDetailedModeEnabled()), "BOOLEAN", "详细回答规则开关");

        // rag.rate-limit
        addConfig(configs, "rag.rate-limit", "enabled",
                str(rateLimitProperties.getGlobalEnabled()), "BOOLEAN", "全局限流开关");
        addConfig(configs, "rag.rate-limit", "maxConcurrent",
                str(rateLimitProperties.getGlobalMaxConcurrent()), "INTEGER", "最大并发数");
        addConfig(configs, "rag.rate-limit", "maxWaitSeconds",
                str(rateLimitProperties.getGlobalMaxWaitSeconds()), "INTEGER", "最大等待秒数");
        addConfig(configs, "rag.rate-limit", "leaseSeconds",
                str(rateLimitProperties.getGlobalLeaseSeconds()), "INTEGER", "许可释放秒数");
        addConfig(configs, "rag.rate-limit", "pollIntervalMs",
                str(rateLimitProperties.getGlobalPollIntervalMs()), "INTEGER", "排队轮询间隔(ms)");

        // rag.memory
        addConfig(configs, "rag.memory", "historyKeepTurns",
                str(memoryProperties.getHistoryKeepTurns()), "INTEGER", "保留原文轮数");
        addConfig(configs, "rag.memory", "inputBudgetTokens",
                str(memoryProperties.getInputBudgetTokens()), "INTEGER", "总输入预算Token数");
        addConfig(configs, "rag.memory", "historyBudgetTokens",
                str(memoryProperties.getHistoryBudgetTokens()), "INTEGER", "历史预算Token数");
        addConfig(configs, "rag.memory", "retrievalBudgetTokens",
                str(memoryProperties.getRetrievalBudgetTokens()), "INTEGER", "检索预算Token数");
        addConfig(configs, "rag.memory", "summaryStartTurns",
                str(memoryProperties.getSummaryStartTurns()), "INTEGER", "开始摘要轮数");
        addConfig(configs, "rag.memory", "summaryEnabled",
                str(memoryProperties.getSummaryEnabled()), "BOOLEAN", "摘要开关");
        addConfig(configs, "rag.memory", "summaryTriggerTokens",
                str(memoryProperties.getSummaryTriggerTokens()), "INTEGER", "摘要触发Token阈值");
        addConfig(configs, "rag.memory", "ttlMinutes",
                str(memoryProperties.getTtlMinutes()), "INTEGER", "缓存过期分钟数");
        addConfig(configs, "rag.memory", "summaryMaxChars",
                str(memoryProperties.getSummaryMaxChars()), "INTEGER", "摘要最大字数");
        addConfig(configs, "rag.memory", "titleMaxLength",
                str(memoryProperties.getTitleMaxLength()), "INTEGER", "标题最大长度");

        // ai.selection
        addConfig(configs, "ai.selection", "failureThreshold",
                str(aiModelProperties.getSelection().getFailureThreshold()), "INTEGER", "失败阈值");
        addConfig(configs, "ai.selection", "openDurationMs",
                str(aiModelProperties.getSelection().getOpenDurationMs()), "LONG", "熔断器打开时长(ms)");

        // ai.stream
        addConfig(configs, "ai.stream", "messageChunkSize",
                str(aiModelProperties.getStream().getMessageChunkSize()), "INTEGER", "消息分块大小");

        for (SystemConfigDO config : configs) {
            configMapper.insert(config);
        }

        log.info("从 YAML 初始化系统配置完成，共{}条", configs.size());
    }

    private List<ConfigItem> buildGroupItems(String group, Map<String, SystemConfigDO> dbKV) {
        // 构建每个分组的配置项（优先取 DB 值，否则取内存值）
        List<ConfigItem> items = new ArrayList<>();

        switch (group) {
            case "rag.default" -> {
                items.add(configItem("collectionName", dbKV, str(ragDefaultProperties.getCollectionName()), "STRING", "默认集合名称"));
                items.add(configItem("dimension", dbKV, str(ragDefaultProperties.getDimension()), "INTEGER", "向量维度"));
                items.add(configItem("metricType", dbKV, str(ragDefaultProperties.getMetricType()), "STRING", "度量类型"));
            }
            case "rag.query-rewrite" -> {
                items.add(configItem("enabled", dbKV, str(ragConfigProperties.getQueryRewriteEnabled()), "BOOLEAN", "查询重写开关"));
                items.add(configItem("maxHistoryMessages", dbKV, str(ragConfigProperties.getQueryRewriteMaxHistoryMessages()), "INTEGER", "改写最大历史消息数"));
                items.add(configItem("maxHistoryTokens", dbKV, str(ragConfigProperties.getQueryRewriteMaxHistoryTokens()), "INTEGER", "改写最大历史Token数"));
                items.add(configItem("maxHistoryChars", dbKV, str(ragConfigProperties.getQueryRewriteMaxHistoryChars()), "INTEGER", "改写最大历史字符数"));
            }
            case "rag.prompt-progressive" -> {
                items.add(configItem("enabled", dbKV, str(ragConfigProperties.getPromptProgressiveEnabled()), "BOOLEAN", "渐进式披露总开关"));
                items.add(configItem("coreEnabled", dbKV, str(ragConfigProperties.getPromptProgressiveCoreEnabled()), "BOOLEAN", "核心规则层开关"));
                items.add(configItem("optionalMultiQuestionEnabled", dbKV, str(ragConfigProperties.getPromptProgressiveOptionalMultiQuestionEnabled()), "BOOLEAN", "多子问题规则开关"));
                items.add(configItem("optionalLinkMediaEnabled", dbKV, str(ragConfigProperties.getPromptProgressiveOptionalLinkMediaEnabled()), "BOOLEAN", "链接/图片规则开关"));
                items.add(configItem("optionalDetailedModeEnabled", dbKV, str(ragConfigProperties.getPromptProgressiveOptionalDetailedModeEnabled()), "BOOLEAN", "详细回答规则开关"));
            }
            case "rag.rate-limit" -> {
                items.add(configItem("enabled", dbKV, str(rateLimitProperties.getGlobalEnabled()), "BOOLEAN", "全局限流开关"));
                items.add(configItem("maxConcurrent", dbKV, str(rateLimitProperties.getGlobalMaxConcurrent()), "INTEGER", "最大并发数"));
                items.add(configItem("maxWaitSeconds", dbKV, str(rateLimitProperties.getGlobalMaxWaitSeconds()), "INTEGER", "最大等待秒数"));
                items.add(configItem("leaseSeconds", dbKV, str(rateLimitProperties.getGlobalLeaseSeconds()), "INTEGER", "许可释放秒数"));
                items.add(configItem("pollIntervalMs", dbKV, str(rateLimitProperties.getGlobalPollIntervalMs()), "INTEGER", "排队轮询间隔(ms)"));
            }
            case "rag.memory" -> {
                items.add(configItem("historyKeepTurns", dbKV, str(memoryProperties.getHistoryKeepTurns()), "INTEGER", "保留原文轮数"));
                items.add(configItem("inputBudgetTokens", dbKV, str(memoryProperties.getInputBudgetTokens()), "INTEGER", "总输入预算Token数"));
                items.add(configItem("historyBudgetTokens", dbKV, str(memoryProperties.getHistoryBudgetTokens()), "INTEGER", "历史预算Token数"));
                items.add(configItem("retrievalBudgetTokens", dbKV, str(memoryProperties.getRetrievalBudgetTokens()), "INTEGER", "检索预算Token数"));
                items.add(configItem("summaryStartTurns", dbKV, str(memoryProperties.getSummaryStartTurns()), "INTEGER", "开始摘要轮数"));
                items.add(configItem("summaryEnabled", dbKV, str(memoryProperties.getSummaryEnabled()), "BOOLEAN", "摘要开关"));
                items.add(configItem("summaryTriggerTokens", dbKV, str(memoryProperties.getSummaryTriggerTokens()), "INTEGER", "摘要触发Token阈值"));
                items.add(configItem("ttlMinutes", dbKV, str(memoryProperties.getTtlMinutes()), "INTEGER", "缓存过期分钟数"));
                items.add(configItem("summaryMaxChars", dbKV, str(memoryProperties.getSummaryMaxChars()), "INTEGER", "摘要最大字数"));
                items.add(configItem("titleMaxLength", dbKV, str(memoryProperties.getTitleMaxLength()), "INTEGER", "标题最大长度"));
            }
            case "ai.selection" -> {
                items.add(configItem("failureThreshold", dbKV, str(aiModelProperties.getSelection().getFailureThreshold()), "INTEGER", "失败阈值"));
                items.add(configItem("openDurationMs", dbKV, str(aiModelProperties.getSelection().getOpenDurationMs()), "LONG", "熔断器打开时长(ms)"));
            }
            case "ai.stream" -> {
                items.add(configItem("messageChunkSize", dbKV, str(aiModelProperties.getStream().getMessageChunkSize()), "INTEGER", "消息分块大小"));
            }
        }

        return items;
    }

    private ConfigItem configItem(String key, Map<String, SystemConfigDO> dbKV, String memoryValue, String valueType, String description) {
        SystemConfigDO dbRecord = dbKV.get(key);
        String value = dbRecord != null ? dbRecord.getConfigValue() : memoryValue;
        return ConfigItem.builder()
                .key(key)
                .value(value)
                .valueType(valueType)
                .description(description)
                .build();
    }

    private void addConfig(List<SystemConfigDO> list, String group, String key, String value, String valueType, String description) {
        list.add(SystemConfigDO.builder()
                .configGroup(group)
                .configKey(key)
                .configValue(value)
                .valueType(valueType)
                .description(description)
                .build());
    }

    private String str(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String inferValueType(String value) {
        if (value == null) return "STRING";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "BOOLEAN";
        try {
            Integer.parseInt(value);
            return "INTEGER";
        } catch (Exception ignored) {
        }
        try {
            Long.parseLong(value);
            return "LONG";
        } catch (Exception ignored) {
        }
        return "STRING";
    }
}
