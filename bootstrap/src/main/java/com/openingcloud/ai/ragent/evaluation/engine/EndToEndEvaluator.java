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

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 端到端评估器
 * 提供正确率评分、兜底检测和 Bad Case 归因分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndToEndEvaluator {

    private static final String CORRECTNESS_TEMPLATE = "prompt/eval-correctness.st";
    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "未检索到", "没有找到", "抱歉", "无法回答", "我不确定", "暂无相关", "无法找到");

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 正确率评分
     */
    public JudgeResult judgeCorrectness(String query, String expectedAnswer, String actualAnswer) {
        String prompt = promptTemplateLoader.render(CORRECTNESS_TEMPLATE, Map.of(
                "query", StrUtil.emptyIfNull(query),
                "expected_answer", StrUtil.emptyIfNull(expectedAnswer),
                "actual_answer", StrUtil.emptyIfNull(actualAnswer)));
        return callJudge(prompt);
    }

    /**
     * 兜底检测
     */
    public boolean isFallback(String answer) {
        if (StrUtil.isBlank(answer)) {
            return true;
        }
        String lower = answer.toLowerCase();
        return FALLBACK_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Bad Case 归因
     */
    public String diagnoseRootCause(double hitRate, double recall,
                                    int faithfulnessScore, int correctnessScore) {
        // Priority 1: Retrieval failure
        if (hitRate == 0 || recall < 0.2) {
            return "RETRIEVAL";
        }
        // Priority 2: Generation hallucination
        if (faithfulnessScore <= 2) {
            return "GENERATION";
        }
        // Priority 3: Knowledge gap
        if (correctnessScore <= 2 && faithfulnessScore >= 4) {
            return "KNOWLEDGE_GAP";
        }
        return null;
    }

    private JudgeResult callJudge(String prompt) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.1)
                    .build();
            String response = llmService.chat(request);
            return parseJudgeResponse(response);
        } catch (Exception e) {
            log.error("LLM Judge 调用失败", e);
            return new JudgeResult(0, "评分失败: " + e.getMessage());
        }
    }

    private JudgeResult parseJudgeResponse(String response) {
        try {
            // Try to extract JSON from response
            String json = response;
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = response.substring(start, end + 1);
            }
            JSONObject obj = JSON.parseObject(json);
            int score = obj.getIntValue("score");
            String reason = obj.getString("reason");
            // Clamp score to 1-5
            score = Math.max(1, Math.min(5, score));
            return new JudgeResult(score, StrUtil.emptyIfNull(reason));
        } catch (Exception e) {
            log.warn("解析 Judge 响应失败: {}", response, e);
            return new JudgeResult(0, "解析失败: " + response);
        }
    }
}
