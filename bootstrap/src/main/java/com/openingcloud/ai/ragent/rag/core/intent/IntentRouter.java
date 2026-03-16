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

package com.openingcloud.ai.ragent.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import com.openingcloud.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.openingcloud.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.openingcloud.ai.ragent.rag.dto.IntentGroup;
import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图路由器。
 *
 * <p>编排 闲聊检测 → 意图识别 → 歧义引导 的完整链路，
 * 输出统一的 {@link RoutingDecision} 供下游选择处理路径。
 *
 * <p>路由优先级：
 * <ol>
 *   <li>闲聊快筛（ChitchatDetector，微秒级规则）</li>
 *   <li>LLM 意图分类（IntentResolver，含规则强制匹配）</li>
 *   <li>歧义检测（IntentGuidanceService）→ CLARIFICATION</li>
 *   <li>按意图类型分流：SYSTEM / TOOL / KNOWLEDGE / MIXED</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouter {

    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;

    /**
     * 完整路由：闲聊检测 + 意图识别 + 歧义引导。
     *
     * @param question 用户原始问题
     * @param token    取消令牌
     * @return 路由决策
     */
    public RoutingDecision route(String question, CancellationToken token) {
        if (ChitchatDetector.isChitchat(question)) {
            log.debug("闲聊快筛命中，跳过意图识别 - question={}", question);
            return RoutingDecision.chitchat();
        }

        List<SubQuestionIntent> subIntents = intentResolver.resolveFromQuestion(question, token);
        return routeFromIntents(question, subIntents);
    }

    /**
     * 从已有的意图结果进行路由（用于意图已在上游完成的场景）。
     */
    public RoutingDecision routeFromIntents(String question, List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents) || allEmpty(subIntents)) {
            log.debug("意图识别结果为空，降级为知识库检索 - question={}", question);
            return RoutingDecision.knowledge(subIntents);
        }

        List<NodeScore> allScores = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();

        if (intentResolver.isSystemOnly(allScores)) {
            return RoutingDecision.system(subIntents);
        }

        GuidanceDecision guidance = guidanceService.detectAmbiguity(question, subIntents);
        if (guidance.isPrompt()) {
            return RoutingDecision.clarification(subIntents, guidance.getPrompt());
        }

        IntentGroup group = intentResolver.mergeIntentGroup(subIntents);
        boolean hasKb = CollUtil.isNotEmpty(group.kbIntents());
        boolean hasMcp = CollUtil.isNotEmpty(group.mcpIntents());

        if (hasMcp && hasKb) {
            return RoutingDecision.mixed(subIntents);
        }
        if (hasMcp) {
            return RoutingDecision.tool(subIntents);
        }
        return RoutingDecision.knowledge(subIntents);
    }

    private boolean allEmpty(List<SubQuestionIntent> subIntents) {
        return subIntents.stream().allMatch(si -> CollUtil.isEmpty(si.nodeScores()));
    }
}
