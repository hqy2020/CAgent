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

package com.openingcloud.ai.ragent.rag.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.web.Results;
import com.openingcloud.ai.ragent.rag.controller.request.ModelCandidateRequest;
import com.openingcloud.ai.ragent.rag.controller.request.ModelProviderRequest;
import com.openingcloud.ai.ragent.rag.controller.request.ModelTestRequest;
import com.openingcloud.ai.ragent.rag.controller.vo.AIModelCandidateVO;
import com.openingcloud.ai.ragent.rag.controller.vo.AIModelProviderVO;
import com.openingcloud.ai.ragent.rag.controller.vo.AIModelTestResultVO;
import com.openingcloud.ai.ragent.rag.dao.entity.AIModelCandidateDO;
import com.openingcloud.ai.ragent.rag.dao.entity.AIModelProviderDO;
import com.openingcloud.ai.ragent.rag.service.AIModelManagementService;
import com.openingcloud.ai.ragent.rag.service.AIModelTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 模型管理控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AIModelController {

    private final AIModelManagementService modelService;
    private final AIModelTestService modelTestService;
    private final ObjectMapper objectMapper;

    private static final String API_KEY_MASK_PATTERN = "****";

    @GetMapping("/ai/providers")
    public Result<List<AIModelProviderVO>> listProviders() {
        List<AIModelProviderDO> providers = modelService.listProviders();
        List<AIModelProviderVO> vos = providers.stream().map(this::toProviderVO).collect(Collectors.toList());
        return Results.success(vos);
    }

    @PostMapping("/ai/providers")
    public Result<String> createProvider(@RequestBody ModelProviderRequest request) {
        return Results.success(String.valueOf(modelService.createProvider(request)));
    }

    @PutMapping("/ai/providers/{id}")
    public Result<Void> updateProvider(@PathVariable String id, @RequestBody ModelProviderRequest request) {
        modelService.updateProvider(Long.parseLong(id), request);
        return Results.success();
    }

    @DeleteMapping("/ai/providers/{id}")
    public Result<Void> deleteProvider(@PathVariable String id) {
        modelService.deleteProvider(Long.parseLong(id));
        return Results.success();
    }

    @GetMapping("/ai/models")
    public Result<List<AIModelCandidateVO>> listCandidates(@RequestParam(required = false) String type) {
        List<AIModelCandidateDO> candidates = modelService.listCandidates(type);
        List<AIModelCandidateVO> vos = candidates.stream().map(this::toCandidateVO).collect(Collectors.toList());
        return Results.success(vos);
    }

    @PostMapping("/ai/models")
    public Result<String> createCandidate(@RequestBody ModelCandidateRequest request) {
        return Results.success(String.valueOf(modelService.createCandidate(request)));
    }

    @PutMapping("/ai/models/{id}")
    public Result<Void> updateCandidate(@PathVariable String id, @RequestBody ModelCandidateRequest request) {
        modelService.updateCandidate(Long.parseLong(id), request);
        return Results.success();
    }

    @DeleteMapping("/ai/models/{id}")
    public Result<Void> deleteCandidate(@PathVariable String id) {
        modelService.deleteCandidate(Long.parseLong(id));
        return Results.success();
    }

    @PutMapping("/ai/models/{id}/default")
    public Result<Void> setDefaultModel(@PathVariable String id) {
        modelService.setDefaultModel(Long.parseLong(id));
        return Results.success();
    }

    @PutMapping("/ai/models/{id}/deep-thinking")
    public Result<Void> setDeepThinkingModel(@PathVariable String id) {
        modelService.setDeepThinkingModel(Long.parseLong(id));
        return Results.success();
    }

    @PostMapping("/ai/models/{id}/test")
    public Result<AIModelTestResultVO> testModel(@PathVariable String id,
                                                  @RequestBody(required = false) ModelTestRequest request) {
        AIModelTestResultVO result = modelTestService.testModel(Long.parseLong(id), request);
        return Results.success(result);
    }

    private AIModelProviderVO toProviderVO(AIModelProviderDO entity) {
        return AIModelProviderVO.builder()
                .id(String.valueOf(entity.getId()))
                .providerKey(entity.getProviderKey())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .apiKey(maskApiKey(entity.getApiKey()))
                .endpoints(parseEndpoints(entity.getEndpoints()))
                .enabled(entity.getEnabled())
                .sortOrder(entity.getSortOrder())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private AIModelCandidateVO toCandidateVO(AIModelCandidateDO entity) {
        return AIModelCandidateVO.builder()
                .id(String.valueOf(entity.getId()))
                .modelId(entity.getModelId())
                .modelType(entity.getModelType())
                .providerKey(entity.getProviderKey())
                .modelName(entity.getModelName())
                .customUrl(entity.getCustomUrl())
                .dimension(entity.getDimension())
                .priority(entity.getPriority())
                .enabled(entity.getEnabled())
                .supportsThinking(entity.getSupportsThinking())
                .isDefault(entity.getIsDefault())
                .isDeepThinking(entity.getIsDeepThinking())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        if (apiKey.length() <= 8) return API_KEY_MASK_PATTERN;
        return apiKey.substring(0, 4) + API_KEY_MASK_PATTERN;
    }

    private Map<String, String> parseEndpoints(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
