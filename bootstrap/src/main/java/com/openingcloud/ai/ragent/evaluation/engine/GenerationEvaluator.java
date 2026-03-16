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
 * 生成阶段评估器
 * 通过 LLM Judge 评估回答的忠实度和相关性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationEvaluator {

    private static final String FAITHFULNESS_TEMPLATE = "prompt/eval-faithfulness.st";
    private static final String RELEVANCY_TEMPLATE = "prompt/eval-relevancy.st";

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 忠实度评分: 评估回答是否忠于参考文档
     */
    public JudgeResult judgeFaithfulness(String context, String answer) {
        String prompt = promptTemplateLoader.render(FAITHFULNESS_TEMPLATE, Map.of(
                "context", StrUtil.emptyIfNull(context),
                "answer", StrUtil.emptyIfNull(answer)));
        return callJudge(prompt);
    }

    /**
     * 相关性评分: 评估回答是否与用户问题相关
     */
    public JudgeResult judgeRelevancy(String query, String answer) {
        String prompt = promptTemplateLoader.render(RELEVANCY_TEMPLATE, Map.of(
                "query", StrUtil.emptyIfNull(query),
                "answer", StrUtil.emptyIfNull(answer)));
        return callJudge(prompt);
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
