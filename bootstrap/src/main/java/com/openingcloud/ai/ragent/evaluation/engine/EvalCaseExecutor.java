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

package com.openingcloud.ai.ragent.evaluation.engine;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalDatasetCaseDO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalRunResultDO;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.convention.RetrievedChunk;
import com.openingcloud.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.openingcloud.ai.ragent.rag.dto.RetrievalContext;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 评测用例执行器
 * 负责对单个评测用例执行完整的 RAG 评测流程：检索 -> 生成 -> 评估
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCaseExecutor {

    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RetrievalEvaluator retrievalEvaluator;
    private final GenerationEvaluator generationEvaluator;
    private final EndToEndEvaluator endToEndEvaluator;

    /**
     * 执行单个评测用例
     *
     * @param evalCase 评测用例
     * @return 评测结果
     */
    public EvalRunResultDO execute(EvalDatasetCaseDO evalCase) {
        long startTime = System.currentTimeMillis();
        EvalRunResultDO result = new EvalRunResultDO();
        result.setCaseId(evalCase.getId());

        try {
            // 解析相关分块 ID
            List<String> relevantChunkIds = parseChunkIds(evalCase.getRelevantChunkIds());

            // Step 1: 检索
            SubQuestionIntent intent = new SubQuestionIntent(evalCase.getQuery(), List.of());
            RetrievalContext retrievalContext = retrievalEngine.retrieve(List.of(intent), 10);

            // 提取检索到的分块 ID
            List<String> retrievedChunkIds = extractChunkIds(retrievalContext);
            result.setRetrievedChunkIds(JSON.toJSONString(retrievedChunkIds));

            // Step 2: 检索评估
            if (CollUtil.isNotEmpty(relevantChunkIds)) {
                result.setHitRate(BigDecimal.valueOf(retrievalEvaluator.hitRate(retrievedChunkIds, relevantChunkIds)));
                result.setMrr(BigDecimal.valueOf(retrievalEvaluator.mrr(retrievedChunkIds, relevantChunkIds)));
                result.setRecallScore(BigDecimal.valueOf(retrievalEvaluator.recall(retrievedChunkIds, relevantChunkIds)));
                result.setPrecisionScore(BigDecimal.valueOf(retrievalEvaluator.precision(retrievedChunkIds, relevantChunkIds)));
            }

            // Step 3: 生成答案
            String kbContext = retrievalContext.getKbContext();
            String systemPrompt = "你是一个知识库问答助手。请根据参考文档回答用户问题。\n\n参考文档：\n" + StrUtil.emptyIfNull(kbContext);
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(evalCase.getQuery())
                    ))
                    .temperature(0.3)
                    .build();
            String generatedAnswer = llmService.chat(chatRequest);
            result.setGeneratedAnswer(generatedAnswer);

            // Step 4: 生成评估（忠实度 + 相关性）
            JudgeResult faithfulness = generationEvaluator.judgeFaithfulness(kbContext, generatedAnswer);
            result.setFaithfulnessScore(BigDecimal.valueOf(faithfulness.score()));
            result.setFaithfulnessReason(faithfulness.reason());

            JudgeResult relevancy = generationEvaluator.judgeRelevancy(evalCase.getQuery(), generatedAnswer);
            result.setRelevancyScore(BigDecimal.valueOf(relevancy.score()));
            result.setRelevancyReason(relevancy.reason());

            // Step 5: 端到端评估
            if (StrUtil.isNotBlank(evalCase.getExpectedAnswer())) {
                JudgeResult correctness = endToEndEvaluator.judgeCorrectness(
                        evalCase.getQuery(), evalCase.getExpectedAnswer(), generatedAnswer);
                result.setCorrectnessScore(BigDecimal.valueOf(correctness.score()));
                result.setCorrectnessReason(correctness.reason());
            }

            // 兜底检测
            boolean fallback = endToEndEvaluator.isFallback(generatedAnswer);
            result.setIsFallback(fallback ? 1 : 0);

            // Bad Case 诊断
            double hitRate = result.getHitRate() != null ? result.getHitRate().doubleValue() : 1.0;
            double recall = result.getRecallScore() != null ? result.getRecallScore().doubleValue() : 1.0;
            int fScore = faithfulness.score();
            int cScore = result.getCorrectnessScore() != null ? result.getCorrectnessScore().intValue() : 5;

            String rootCause = endToEndEvaluator.diagnoseRootCause(hitRate, recall, fScore, cScore);
            result.setRootCause(rootCause);
            result.setIsBadCase((cScore < 4 || rootCause != null) ? 1 : 0);

        } catch (Exception e) {
            log.error("评测用例执行失败, caseId={}", evalCase.getId(), e);
            result.setGeneratedAnswer("执行异常: " + e.getMessage());
            result.setIsBadCase(1);
        }

        result.setLatencyMs(System.currentTimeMillis() - startTime);
        return result;
    }

    private List<String> parseChunkIds(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> extractChunkIds(RetrievalContext context) {
        if (context == null || context.getIntentChunks() == null) {
            return List.of();
        }
        return context.getIntentChunks().values().stream()
                .flatMap(List::stream)
                .map(RetrievedChunk::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
