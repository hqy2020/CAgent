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

import com.openingcloud.ai.ragent.rag.dto.SubQuestionIntent;

import java.util.List;

/**
 * 意图路由决策结果。
 *
 * <p>由 {@link IntentRouter} 生成，指导下游选择不同的处理路径：
 * <ul>
 *   <li>CHITCHAT — 跳过检索，直接由模型回复</li>
 *   <li>SYSTEM — 系统交互（欢迎语等），无需知识库</li>
 *   <li>KNOWLEDGE — 走 RAG 检索 + Query 重写</li>
 *   <li>TOOL — 走 MCP 工具调用</li>
 *   <li>MIXED — 同时包含 KB 和 MCP 意图</li>
 *   <li>CLARIFICATION — 歧义引导，需要反问用户</li>
 * </ul>
 *
 * @param path                路由路径
 * @param subIntents          意图识别结果（CHITCHAT 时为空列表）
 * @param clarificationPrompt 歧义引导提示（仅 CLARIFICATION 路径非空）
 */
public record RoutingDecision(
        RoutingPath path,
        List<SubQuestionIntent> subIntents,
        String clarificationPrompt
) {

    public enum RoutingPath {
        CHITCHAT,
        SYSTEM,
        KNOWLEDGE,
        TOOL,
        MIXED,
        CLARIFICATION
    }

    public static RoutingDecision chitchat() {
        return new RoutingDecision(RoutingPath.CHITCHAT, List.of(), null);
    }

    public static RoutingDecision system(List<SubQuestionIntent> subIntents) {
        return new RoutingDecision(RoutingPath.SYSTEM, subIntents, null);
    }

    public static RoutingDecision knowledge(List<SubQuestionIntent> subIntents) {
        return new RoutingDecision(RoutingPath.KNOWLEDGE, subIntents, null);
    }

    public static RoutingDecision tool(List<SubQuestionIntent> subIntents) {
        return new RoutingDecision(RoutingPath.TOOL, subIntents, null);
    }

    public static RoutingDecision mixed(List<SubQuestionIntent> subIntents) {
        return new RoutingDecision(RoutingPath.MIXED, subIntents, null);
    }

    public static RoutingDecision clarification(List<SubQuestionIntent> subIntents, String prompt) {
        return new RoutingDecision(RoutingPath.CLARIFICATION, subIntents, prompt);
    }

    public boolean needsQueryRewrite() {
        return path == RoutingPath.KNOWLEDGE || path == RoutingPath.MIXED;
    }

    public boolean needsRetrieval() {
        return path == RoutingPath.KNOWLEDGE || path == RoutingPath.TOOL || path == RoutingPath.MIXED;
    }
}
