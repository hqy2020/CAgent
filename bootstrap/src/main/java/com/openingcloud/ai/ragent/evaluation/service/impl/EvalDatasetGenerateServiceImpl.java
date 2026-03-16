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

package com.openingcloud.ai.ragent.evaluation.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCaseCreateRequest;
import com.openingcloud.ai.ragent.evaluation.service.EvalDatasetGenerateService;
import com.openingcloud.ai.ragent.evaluation.service.EvalDatasetService;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 评测数据集自动生成服务实现
 * <p>
 * 从知识库中随机抽取文档分块，利用 LLM 生成高质量 QA 对，
 * 自动添加到指定评测数据集中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetGenerateServiceImpl implements EvalDatasetGenerateService {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final LLMService llmService;
    private final EvalDatasetService evalDatasetService;

    private static final String QA_GENERATION_PROMPT_TEMPLATE = """
            你是一个数据集生成专家。请根据以下参考文档片段，生成一个高质量的问答对。

            ## 参考文档
            %s

            ## 要求
            1. 问题应该是一个自然的、用户可能会问的问题
            2. 答案应该完整且准确地基于参考文档
            3. 问题不要包含"根据文档"等提示词

            请以 JSON 格式返回：
            {"question": "<问题>", "answer": "<答案>"}""";

    @Override
    public int generateCases(Long datasetId, Long kbId, int count) {
        // 1. 查询知识库中所有启用的分块
        List<KnowledgeChunkDO> allChunks = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .eq(KnowledgeChunkDO::getKbId, kbId)
                        .eq(KnowledgeChunkDO::getEnabled, 1));

        if (allChunks.isEmpty()) {
            log.warn("知识库 {} 中没有启用的分块，无法生成用例", kbId);
            return 0;
        }

        // 2. 随机打乱，取 min(count*2, all.size()) 个候选分块
        Collections.shuffle(allChunks);
        int candidateCount = Math.min(count * 2, allChunks.size());
        List<KnowledgeChunkDO> candidates = allChunks.subList(0, candidateCount);

        // 3. 逐个生成 QA 对，直到达到目标数量
        int successCount = 0;
        for (KnowledgeChunkDO chunk : candidates) {
            if (successCount >= count) {
                break;
            }
            try {
                String prompt = String.format(QA_GENERATION_PROMPT_TEMPLATE, chunk.getContent());

                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(ChatMessage.user(prompt)))
                        .temperature(0.7)
                        .build();
                String response = llmService.chat(request);

                // 解析 JSON 响应
                JSONObject jsonResponse = extractJson(response);
                if (jsonResponse == null) {
                    log.warn("LLM 返回的内容无法解析为 JSON, chunkId={}", chunk.getId());
                    continue;
                }

                String question = jsonResponse.getString("question");
                String answer = jsonResponse.getString("answer");

                if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
                    log.warn("LLM 生成的 QA 对不完整, chunkId={}", chunk.getId());
                    continue;
                }

                // 构建请求并添加用例
                EvalDatasetCaseCreateRequest caseRequest = new EvalDatasetCaseCreateRequest();
                caseRequest.setQuery(question);
                caseRequest.setExpectedAnswer(answer);
                caseRequest.setRelevantChunkIds(List.of(chunk.getId().toString()));
                caseRequest.setIntent(null);

                evalDatasetService.addCase(datasetId, caseRequest);
                successCount++;
                log.debug("成功生成评测用例, datasetId={}, chunkId={}, question={}", datasetId, chunk.getId(),
                        question);

            } catch (Exception e) {
                log.error("生成评测用例异常, datasetId={}, chunkId={}", datasetId, chunk.getId(), e);
            }
        }

        log.info("评测用例生成完成, datasetId={}, kbId={}, 目标={}, 实际生成={}", datasetId, kbId, count, successCount);
        return successCount;
    }

    /**
     * 从 LLM 响应中提取 JSON 对象，支持 Markdown 代码块包裹的情况
     */
    private JSONObject extractJson(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            // 尝试直接解析
            return JSON.parseObject(response.trim());
        } catch (Exception e) {
            // 尝试从 Markdown 代码块中提取
            try {
                String cleaned = response;
                if (cleaned.contains("```json")) {
                    cleaned = cleaned.substring(cleaned.indexOf("```json") + 7);
                    cleaned = cleaned.substring(0, cleaned.indexOf("```"));
                } else if (cleaned.contains("```")) {
                    cleaned = cleaned.substring(cleaned.indexOf("```") + 3);
                    cleaned = cleaned.substring(0, cleaned.indexOf("```"));
                }
                return JSON.parseObject(cleaned.trim());
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
