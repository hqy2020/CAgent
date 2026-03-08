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

package com.nageoffer.ai.ragent.rag.agent;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 待确认提案存储（Redis）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingProposalStore {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final String KEY_PREFIX = "rag:agent:proposal:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RAGConfigProperties ragConfigProperties;

    public PendingProposal create(String userId,
                                  String conversationId,
                                  MCPRequest request,
                                  String targetPath,
                                  String riskHint) {
        String proposalId = IdUtil.getSnowflakeNextIdStr();
        int ttlMinutes = Math.max(1, Optional.ofNullable(ragConfigProperties.getAgentConfirmationTtlMinutes()).orElse(30));
        long now = System.currentTimeMillis();
        long expiresAt = now + Duration.ofMinutes(ttlMinutes).toMillis();
        PendingProposal proposal = PendingProposal.builder()
                .proposalId(proposalId)
                .userId(userId)
                .conversationId(conversationId)
                .toolId(request.getToolId())
                .userQuestion(request.getUserQuestion())
                .parameters(request.getParameters() == null ? Map.of() : request.getParameters())
                .targetPath(targetPath)
                .riskHint(riskHint)
                .status(STATUS_PENDING)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
        save(proposal, Duration.ofMinutes(ttlMinutes));
        return proposal;
    }

    public ProposalDecisionResult confirm(String proposalId, String conversationId, String userId) {
        Optional<PendingProposal> proposalOpt = findById(proposalId);
        if (proposalOpt.isEmpty()) {
            return ProposalDecisionResult.failure("提案不存在或已过期");
        }
        PendingProposal proposal = proposalOpt.get();
        if (!belongsToConversationAndUser(proposal, conversationId, userId)) {
            return ProposalDecisionResult.failure("无权限确认该提案");
        }
        if (isExpired(proposal)) {
            delete(proposalId);
            return ProposalDecisionResult.failure("提案已过期");
        }
        if (STATUS_CONFIRMED.equals(proposal.getStatus())) {
            return ProposalDecisionResult.failure("提案已被确认");
        }
        if (STATUS_REJECTED.equals(proposal.getStatus())) {
            return ProposalDecisionResult.failure("提案已被拒绝");
        }
        proposal.setStatus(STATUS_CONFIRMED);
        save(proposal, Duration.ofMillis(Math.max(1, proposal.getExpiresAt() - System.currentTimeMillis())));
        return ProposalDecisionResult.success(proposal);
    }

    public ProposalDecisionResult reject(String proposalId, String conversationId, String userId) {
        Optional<PendingProposal> proposalOpt = findById(proposalId);
        if (proposalOpt.isEmpty()) {
            return ProposalDecisionResult.failure("提案不存在或已过期");
        }
        PendingProposal proposal = proposalOpt.get();
        if (!belongsToConversationAndUser(proposal, conversationId, userId)) {
            return ProposalDecisionResult.failure("无权限拒绝该提案");
        }
        if (isExpired(proposal)) {
            delete(proposalId);
            return ProposalDecisionResult.failure("提案已过期");
        }
        if (STATUS_REJECTED.equals(proposal.getStatus())) {
            return ProposalDecisionResult.failure("提案已被拒绝");
        }
        if (STATUS_CONFIRMED.equals(proposal.getStatus())) {
            return ProposalDecisionResult.failure("提案已被确认，不能再拒绝");
        }
        proposal.setStatus(STATUS_REJECTED);
        save(proposal, Duration.ofMillis(Math.max(1, proposal.getExpiresAt() - System.currentTimeMillis())));
        return ProposalDecisionResult.success(proposal);
    }

    public ProposalDecisionResult rollbackToPending(String proposalId,
                                                    String conversationId,
                                                    String userId,
                                                    Map<String, Object> latestParameters) {
        Optional<PendingProposal> proposalOpt = findById(proposalId);
        if (proposalOpt.isEmpty()) {
            return ProposalDecisionResult.failure("提案不存在或已过期");
        }
        PendingProposal proposal = proposalOpt.get();
        if (!belongsToConversationAndUser(proposal, conversationId, userId)) {
            return ProposalDecisionResult.failure("无权限更新该提案");
        }
        if (isExpired(proposal)) {
            delete(proposalId);
            return ProposalDecisionResult.failure("提案已过期");
        }
        if (STATUS_REJECTED.equals(proposal.getStatus())) {
            return ProposalDecisionResult.failure("提案已被拒绝");
        }
        proposal.setStatus(STATUS_PENDING);
        if (latestParameters != null && !latestParameters.isEmpty()) {
            proposal.setParameters(new LinkedHashMap<>(latestParameters));
        }
        save(proposal, Duration.ofMillis(Math.max(1, proposal.getExpiresAt() - System.currentTimeMillis())));
        return ProposalDecisionResult.success(proposal);
    }

    public Optional<PendingProposal> findById(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) {
            return Optional.empty();
        }
        String json = stringRedisTemplate.opsForValue().get(key(proposalId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, PendingProposal.class));
        } catch (Exception e) {
            log.warn("解析提案失败: proposalId={}", proposalId, e);
            return Optional.empty();
        }
    }

    private boolean belongsToConversationAndUser(PendingProposal proposal, String conversationId, String userId) {
        return proposal != null
                && proposal.getConversationId() != null
                && proposal.getConversationId().equals(conversationId)
                && proposal.getUserId() != null
                && proposal.getUserId().equals(userId);
    }

    private boolean isExpired(PendingProposal proposal) {
        return proposal.getExpiresAt() != null && proposal.getExpiresAt() < System.currentTimeMillis();
    }

    private void save(PendingProposal proposal, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(proposal);
            stringRedisTemplate.opsForValue().set(key(proposal.getProposalId()), json, ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化提案失败", e);
        }
    }

    private void delete(String proposalId) {
        stringRedisTemplate.delete(key(proposalId));
    }

    private String key(String proposalId) {
        return KEY_PREFIX + proposalId;
    }

    public record ProposalDecisionResult(
            boolean success,
            PendingProposal proposal,
            String message) {

        static ProposalDecisionResult success(PendingProposal proposal) {
            return new ProposalDecisionResult(true, proposal, null);
        }

        static ProposalDecisionResult failure(String message) {
            return new ProposalDecisionResult(false, null, message);
        }
    }
}
