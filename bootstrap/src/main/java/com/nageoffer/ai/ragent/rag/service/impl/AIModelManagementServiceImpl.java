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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.ModelCandidateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.ModelProviderRequest;
import com.nageoffer.ai.ragent.rag.dao.entity.AIModelCandidateDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AIModelProviderDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AIModelCandidateMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AIModelProviderMapper;
import com.nageoffer.ai.ragent.rag.service.AIModelManagementService;
import com.nageoffer.ai.ragent.rag.service.ConfigSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 模型管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIModelManagementServiceImpl implements AIModelManagementService {

    private final AIModelProviderMapper providerMapper;
    private final AIModelCandidateMapper candidateMapper;
    private final ConfigSyncService configSyncService;
    private final ObjectMapper objectMapper;

    private static final String API_KEY_MASK = "****";

    @Override
    public List<AIModelProviderDO> listProviders() {
        return providerMapper.selectList(
                Wrappers.<AIModelProviderDO>lambdaQuery().orderByAsc(AIModelProviderDO::getSortOrder));
    }

    @Override
    public Long createProvider(ModelProviderRequest request) {
        AIModelProviderDO entity = AIModelProviderDO.builder()
                .providerKey(request.getProviderKey())
                .name(request.getName())
                .baseUrl(request.getBaseUrl())
                .apiKey(request.getApiKey())
                .endpoints(serializeEndpoints(request.getEndpoints()))
                .enabled(request.getEnabled() != null ? request.getEnabled() : 1)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();
        providerMapper.insert(entity);
        configSyncService.syncAIModelProperties();
        return entity.getId();
    }

    @Override
    public void updateProvider(Long id, ModelProviderRequest request) {
        AIModelProviderDO existing = providerMapper.selectById(id);
        if (existing == null) throw new ClientException("提供方不存在");

        existing.setProviderKey(request.getProviderKey());
        existing.setName(request.getName());
        existing.setBaseUrl(request.getBaseUrl());
        // API Key 脱敏处理：含 **** 或为空则不更新
        if (request.getApiKey() != null && !request.getApiKey().contains(API_KEY_MASK)
                && !request.getApiKey().isBlank()) {
            existing.setApiKey(request.getApiKey());
        }
        existing.setEndpoints(serializeEndpoints(request.getEndpoints()));
        if (request.getEnabled() != null) existing.setEnabled(request.getEnabled());
        if (request.getSortOrder() != null) existing.setSortOrder(request.getSortOrder());

        providerMapper.updateById(existing);
        configSyncService.syncAIModelProperties();
    }

    @Override
    public void deleteProvider(Long id) {
        providerMapper.deleteById(id);
        configSyncService.syncAIModelProperties();
    }

    @Override
    public List<AIModelCandidateDO> listCandidates(String type) {
        var query = Wrappers.<AIModelCandidateDO>lambdaQuery()
                .orderByAsc(AIModelCandidateDO::getPriority);
        if (type != null && !type.isBlank()) {
            query.eq(AIModelCandidateDO::getModelType, type);
        }
        return candidateMapper.selectList(query);
    }

    @Override
    public Long createCandidate(ModelCandidateRequest request) {
        AIModelCandidateDO entity = AIModelCandidateDO.builder()
                .modelId(request.getModelId())
                .modelType(request.getModelType())
                .providerKey(request.getProviderKey())
                .modelName(request.getModelName())
                .customUrl(request.getCustomUrl())
                .dimension(request.getDimension())
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .enabled(request.getEnabled() != null ? request.getEnabled() : 1)
                .supportsThinking(request.getSupportsThinking() != null ? request.getSupportsThinking() : 0)
                .isDefault(0)
                .isDeepThinking(0)
                .build();
        candidateMapper.insert(entity);
        configSyncService.syncAIModelProperties();
        return entity.getId();
    }

    @Override
    public void updateCandidate(Long id, ModelCandidateRequest request) {
        AIModelCandidateDO existing = candidateMapper.selectById(id);
        if (existing == null) throw new ClientException("模型候选不存在");

        existing.setModelId(request.getModelId());
        existing.setModelType(request.getModelType());
        existing.setProviderKey(request.getProviderKey());
        existing.setModelName(request.getModelName());
        existing.setCustomUrl(request.getCustomUrl());
        existing.setDimension(request.getDimension());
        if (request.getPriority() != null) existing.setPriority(request.getPriority());
        if (request.getEnabled() != null) existing.setEnabled(request.getEnabled());
        if (request.getSupportsThinking() != null) existing.setSupportsThinking(request.getSupportsThinking());

        candidateMapper.updateById(existing);
        configSyncService.syncAIModelProperties();
    }

    @Override
    public void deleteCandidate(Long id) {
        candidateMapper.deleteById(id);
        configSyncService.syncAIModelProperties();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefaultModel(Long id) {
        AIModelCandidateDO target = candidateMapper.selectById(id);
        if (target == null) throw new ClientException("模型候选不存在");

        // 先清除同类型的所有默认标记
        List<AIModelCandidateDO> sameType = candidateMapper.selectList(
                Wrappers.<AIModelCandidateDO>lambdaQuery()
                        .eq(AIModelCandidateDO::getModelType, target.getModelType())
                        .eq(AIModelCandidateDO::getIsDefault, 1));
        for (AIModelCandidateDO c : sameType) {
            c.setIsDefault(0);
            candidateMapper.updateById(c);
        }

        target.setIsDefault(1);
        candidateMapper.updateById(target);
        configSyncService.syncAIModelProperties();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDeepThinkingModel(Long id) {
        AIModelCandidateDO target = candidateMapper.selectById(id);
        if (target == null) throw new ClientException("模型候选不存在");

        // 先清除同类型的所有深度思考标记
        List<AIModelCandidateDO> sameType = candidateMapper.selectList(
                Wrappers.<AIModelCandidateDO>lambdaQuery()
                        .eq(AIModelCandidateDO::getModelType, target.getModelType())
                        .eq(AIModelCandidateDO::getIsDeepThinking, 1));
        for (AIModelCandidateDO c : sameType) {
            c.setIsDeepThinking(0);
            candidateMapper.updateById(c);
        }

        target.setIsDeepThinking(1);
        candidateMapper.updateById(target);
        configSyncService.syncAIModelProperties();
    }

    private String serializeEndpoints(java.util.Map<String, String> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(endpoints);
        } catch (Exception e) {
            log.warn("序列化 endpoints 失败: {}", e.getMessage());
            return null;
        }
    }
}
