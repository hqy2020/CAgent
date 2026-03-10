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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.infra.chat.ChatClient;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.infra.embedding.EmbeddingClient;
import com.openingcloud.ai.ragent.infra.model.ModelTarget;
import com.openingcloud.ai.ragent.infra.rerank.RerankClient;
import com.openingcloud.ai.ragent.rag.controller.request.ModelTestRequest;
import com.openingcloud.ai.ragent.rag.controller.vo.AIModelTestResultVO;
import com.openingcloud.ai.ragent.rag.dao.entity.AIModelCandidateDO;
import com.openingcloud.ai.ragent.rag.dao.entity.AIModelProviderDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.AIModelCandidateMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.AIModelProviderMapper;
import com.openingcloud.ai.ragent.rag.service.AIModelTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 模型测试服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIModelTestServiceImpl implements AIModelTestService {

    private static final String DEFAULT_CHAT_INPUT = "请回复“测试成功”";
    private static final String DEFAULT_EMBEDDING_INPUT = "模型连通性测试";
    private static final String DEFAULT_RERANK_QUERY = "Spring Boot 默认端口是多少";
    private static final List<String> DEFAULT_RERANK_CANDIDATES = List.of(
            "Spring Boot 默认端口是 8080。",
            "Redis 是高性能内存数据库。",
            "@Transactional 常见失效原因包括方法非 public、自调用等。"
    );

    private final AIModelCandidateMapper candidateMapper;
    private final AIModelProviderMapper providerMapper;
    private final ObjectMapper objectMapper;
    private final List<ChatClient> chatClients;
    private final List<EmbeddingClient> embeddingClients;
    private final List<RerankClient> rerankClients;

    @Override
    public AIModelTestResultVO testModel(Long modelCandidateId, ModelTestRequest request) {
        AIModelCandidateDO candidate = candidateMapper.selectById(modelCandidateId);
        if (candidate == null) {
            throw new ClientException("模型候选不存在");
        }
        AIModelProviderDO provider = providerMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AIModelProviderDO>lambdaQuery()
                        .eq(AIModelProviderDO::getProviderKey, candidate.getProviderKey()));
        if (provider == null) {
            throw new ClientException("模型提供方不存在: " + candidate.getProviderKey());
        }

        long start = System.currentTimeMillis();
        AIModelTestResultVO base = AIModelTestResultVO.builder()
                .success(false)
                .modelType(candidate.getModelType())
                .modelId(candidate.getModelId())
                .providerKey(candidate.getProviderKey())
                .build();

        try {
            ModelTarget target = buildTarget(candidate, provider);
            ModelTestRequest safeRequest = request == null ? new ModelTestRequest() : request;

            AIModelTestResultVO tested = switch (normalizeType(candidate.getModelType())) {
                case "chat" -> doChatTest(base, target, safeRequest);
                case "embedding" -> doEmbeddingTest(base, target, safeRequest);
                case "rerank" -> doRerankTest(base, target, safeRequest);
                default -> throw new ClientException("不支持的模型类型: " + candidate.getModelType());
            };
            tested.setElapsedMs(System.currentTimeMillis() - start);
            return tested;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String message = rootMessage(e);
            log.warn("模型测试失败: modelId={}, type={}, provider={}, error={}",
                    candidate.getModelId(), candidate.getModelType(), candidate.getProviderKey(), message, e);
            return AIModelTestResultVO.builder()
                    .success(false)
                    .modelType(candidate.getModelType())
                    .modelId(candidate.getModelId())
                    .providerKey(candidate.getProviderKey())
                    .elapsedMs(elapsed)
                    .message("调用失败")
                    .errorMessage(message)
                    .build();
        }
    }

    private AIModelTestResultVO doChatTest(AIModelTestResultVO base, ModelTarget target, ModelTestRequest request) {
        ChatClient client = findClient(chatClients, target.candidate().getProvider(), ChatClient::provider, "chat");
        String input = StringUtils.hasText(request.getInput()) ? request.getInput().trim() : DEFAULT_CHAT_INPUT;
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(input)))
                .thinking(Boolean.TRUE.equals(request.getThinking()))
                .temperature(0.2D)
                .topP(0.8D)
                .maxTokens(256)
                .build();
        String response = client.chat(chatRequest, target);
        return copyOf(base)
                .success(true)
                .message("Chat 模型调用成功")
                .responsePreview(truncate(response, 500))
                .build();
    }

    private AIModelTestResultVO doEmbeddingTest(AIModelTestResultVO base, ModelTarget target, ModelTestRequest request) {
        EmbeddingClient client = findClient(embeddingClients, target.candidate().getProvider(), EmbeddingClient::provider, "embedding");
        String input = StringUtils.hasText(request.getInput()) ? request.getInput().trim() : DEFAULT_EMBEDDING_INPUT;
        List<Float> vector = client.embed(input, target);
        List<Float> preview = vector == null ? List.of() : vector.stream().limit(8).collect(Collectors.toList());
        return copyOf(base)
                .success(true)
                .message("Embedding 模型调用成功")
                .vectorDimension(vector == null ? 0 : vector.size())
                .vectorPreview(preview)
                .build();
    }

    private AIModelTestResultVO doRerankTest(AIModelTestResultVO base, ModelTarget target, ModelTestRequest request) {
        RerankClient client = findClient(rerankClients, target.candidate().getProvider(), RerankClient::provider, "rerank");
        String query = StringUtils.hasText(request.getQuery()) ? request.getQuery().trim() : DEFAULT_RERANK_QUERY;
        List<String> candidateTexts = normalizeCandidates(request.getCandidates());
        int topN = request.getTopN() != null && request.getTopN() > 0
                ? Math.min(request.getTopN(), candidateTexts.size())
                : Math.min(3, candidateTexts.size());
        List<RetrievedChunk> chunks = new ArrayList<>(candidateTexts.size());
        for (int i = 0; i < candidateTexts.size(); i++) {
            chunks.add(RetrievedChunk.builder()
                    .id(String.valueOf(i + 1))
                    .text(candidateTexts.get(i))
                    .score(0f)
                    .build());
        }

        List<RetrievedChunk> reranked = client.rerank(query, chunks, topN, target);
        List<AIModelTestResultVO.RerankItem> items = new ArrayList<>();
        for (int i = 0; i < reranked.size(); i++) {
            RetrievedChunk chunk = reranked.get(i);
            items.add(AIModelTestResultVO.RerankItem.builder()
                    .rank(i + 1)
                    .score(chunk.getScore())
                    .text(truncate(chunk.getText(), 160))
                    .build());
        }
        return copyOf(base)
                .success(true)
                .message("Rerank 模型调用成功")
                .rerankResults(items)
                .build();
    }

    private String normalizeType(String modelType) {
        return modelType == null ? "" : modelType.trim().toLowerCase();
    }

    private List<String> normalizeCandidates(List<String> candidates) {
        if (candidates == null) {
            return new ArrayList<>(DEFAULT_RERANK_CANDIDATES);
        }
        List<String> normalized = candidates.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(ArrayList::new));
        if (normalized.size() < 2) {
            return new ArrayList<>(DEFAULT_RERANK_CANDIDATES);
        }
        return normalized;
    }

    private ModelTarget buildTarget(AIModelCandidateDO candidateDO, AIModelProviderDO providerDO) {
        com.openingcloud.ai.ragent.infra.config.AIModelProperties.ModelCandidate candidate =
                new com.openingcloud.ai.ragent.infra.config.AIModelProperties.ModelCandidate();
        candidate.setId(candidateDO.getModelId());
        candidate.setProvider(candidateDO.getProviderKey());
        candidate.setModel(candidateDO.getModelName());
        candidate.setUrl(candidateDO.getCustomUrl());
        candidate.setDimension(candidateDO.getDimension());
        candidate.setPriority(candidateDO.getPriority());
        candidate.setEnabled(candidateDO.getEnabled() != null && candidateDO.getEnabled() == 1);
        candidate.setSupportsThinking(candidateDO.getSupportsThinking() != null && candidateDO.getSupportsThinking() == 1);

        com.openingcloud.ai.ragent.infra.config.AIModelProperties.ProviderConfig provider =
                new com.openingcloud.ai.ragent.infra.config.AIModelProperties.ProviderConfig();
        provider.setUrl(providerDO.getBaseUrl());
        provider.setApiKey(providerDO.getApiKey());
        provider.setEndpoints(parseEndpoints(providerDO.getEndpoints()));

        return new ModelTarget(candidateDO.getModelId(), candidate, provider);
    }

    private Map<String, String> parseEndpoints(String endpoints) {
        if (!StringUtils.hasText(endpoints)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(endpoints, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("解析 provider endpoints 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private <T> T findClient(List<T> clients, String providerKey,
                             java.util.function.Function<T, String> providerFunc,
                             String capability) {
        if (!StringUtils.hasText(providerKey)) {
            throw new ClientException("模型 provider 为空，无法执行 " + capability + " 测试");
        }
        return clients.stream()
                .filter(client -> {
                    String provider = providerFunc.apply(client);
                    return StringUtils.hasText(provider) && providerKey.equalsIgnoreCase(provider);
                })
                .findFirst()
                .orElseThrow(() -> new ClientException(
                        "未找到 " + capability + " 客户端，请检查 provider 配置: " + providerKey));
    }

    private AIModelTestResultVO.AIModelTestResultVOBuilder copyOf(AIModelTestResultVO base) {
        return AIModelTestResultVO.builder()
                .success(base.getSuccess())
                .modelType(base.getModelType())
                .modelId(base.getModelId())
                .providerKey(base.getProviderKey())
                .elapsedMs(base.getElapsedMs())
                .message(base.getMessage())
                .responsePreview(base.getResponsePreview())
                .vectorDimension(base.getVectorDimension())
                .vectorPreview(base.getVectorPreview())
                .rerankResults(base.getRerankResults())
                .errorMessage(base.getErrorMessage());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.toString() : current.getMessage();
    }
}
