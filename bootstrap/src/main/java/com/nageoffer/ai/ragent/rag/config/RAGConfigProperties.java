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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * 改写时用于承接上下文的最大历史消息数
     */
    @Value("${rag.query-rewrite.max-history-messages:4}")
    private Integer queryRewriteMaxHistoryMessages;

    /**
     * 改写时用于承接上下文的最大历史 token 数
     */
    @Value("${rag.query-rewrite.max-history-tokens:600}")
    private Integer queryRewriteMaxHistoryTokens;

    /**
     * 改写时用于承接上下文的最大字符数
     */
    @Value("${rag.query-rewrite.max-history-chars:500}")
    private Integer queryRewriteMaxHistoryChars;

    /**
     * 渐进式披露总开关
     * false 时仅加载场景模板，不叠加 core/optional 规则层
     */
    @Value("${rag.prompt-progressive.enabled:true}")
    private Boolean promptProgressiveEnabled;

    /**
     * 渐进式披露：core 规则层开关
     */
    @Value("${rag.prompt-progressive.core-enabled:true}")
    private Boolean promptProgressiveCoreEnabled;

    /**
     * 渐进式披露：多子问题附加规则开关
     */
    @Value("${rag.prompt-progressive.optional-multi-question-enabled:true}")
    private Boolean promptProgressiveOptionalMultiQuestionEnabled;

    /**
     * 渐进式披露：链接/图片规则开关
     */
    @Value("${rag.prompt-progressive.optional-link-media-enabled:true}")
    private Boolean promptProgressiveOptionalLinkMediaEnabled;

    /**
     * 渐进式披露：详细回答模式规则开关
     */
    @Value("${rag.prompt-progressive.optional-detailed-mode-enabled:true}")
    private Boolean promptProgressiveOptionalDetailedModeEnabled;

    @Value("${rag.chat.kb-temperature:0.3}")
    private Double chatKbTemperature;

    @Value("${rag.chat.kb-top-p:0.85}")
    private Double chatKbTopP;

    @Value("${rag.chat.system-temperature:0.7}")
    private Double chatSystemTemperature;

    @Value("${rag.chat.system-top-p:0.8}")
    private Double chatSystemTopP;

    @Value("${rag.chat.max-tokens-system:2048}")
    private Integer chatMaxTokensSystem;

    @Value("${rag.chat.max-tokens-kb:4096}")
    private Integer chatMaxTokensKb;

    @Value("${rag.agent.enabled:true}")
    private Boolean agentEnabled;

    @Value("${rag.agent.max-loops:3}")
    private Integer agentMaxLoops;

    @Value("${rag.agent.max-steps-per-loop:6}")
    private Integer agentMaxStepsPerLoop;

    @Value("${rag.agent.low-confidence-threshold:0.55}")
    private Double agentLowConfidenceThreshold;

    @Value("${rag.agent.confirmation-ttl-minutes:30}")
    private Integer agentConfirmationTtlMinutes;
}
