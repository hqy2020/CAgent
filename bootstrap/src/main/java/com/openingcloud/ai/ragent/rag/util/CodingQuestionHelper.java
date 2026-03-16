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

package com.openingcloud.ai.ragent.rag.util;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 识别应直接走代码回答的实现类/审查类问题。
 */
public final class CodingQuestionHelper {

    public enum CodingIntentType {
        CODE_IMPLEMENTATION,
        CODE_REVIEW,
        GENERAL
    }

    public record CodingIntentDecision(CodingIntentType type,
                                       boolean inheritedFromContext,
                                       String reason) {

        public boolean isCoding() {
            return type != CodingIntentType.GENERAL;
        }
    }

    private static final Pattern IMPLEMENTATION_HINT = Pattern.compile(
            "(实现|写法|示例|例子|demo|样例|样例代码|示例代码|最方便|最简单|推荐|选哪个|哪种方法|怎么写|怎么做|如何做|代码|如何实现|实现方式|怎么实现|交替打印|轮流打印|按顺序打印|最终输出|implement|implementation|sample code|example code|example)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMPLEMENTATION_FOLLOWUP_HINT = Pattern.compile(
            "(给我代码|给我具体的代码|直接给代码|完整代码|具体代码|贴代码|修正版代码|改成代码|代码版本|给出实现|直接实现|把代码给我)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REVIEW_HINT = Pattern.compile(
            "(正确吗|对吗|有问题吗|有没有问题|哪里有问题|能用吗|可用吗|合理吗|靠谱吗|线程安全|安全吗|会不会有问题|bug|review|code review|检查代码|代码检查|代码审查|帮我看下|帮我检查|帮我review|帮我 review|审查一下|分析一下这段代码)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REVIEW_FOLLOWUP_HINT = Pattern.compile(
            "(这段呢|这样行吗|这样对吗|这行呢|帮我看下这段|帮我看看这段|帮我检查一下|有问题吗|靠谱吗|哪里不对)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PROGRAMMING_SEMANTIC_HINT = Pattern.compile(
            "(线程|锁|并发|java|jvm|spring|sql|类|函数|方法|接口|线程池|aqs|cas|lambda|注解|hashmap|concurrenthashmap|volatile|synchronized|countdownlatch|cyclicbarrier|semaphore|condition)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CODE_SNIPPET_HINT = Pattern.compile(
            "(?s)(```.+```|\\bclass\\b|\\binterface\\b|\\benum\\b|\\bpublic\\b|\\bprivate\\b|\\bprotected\\b|\\bstatic\\b|\\bvoid\\b|\\bnew\\b|\\breturn\\b|\\bif\\b|\\belse\\b|\\bfor\\b|\\bwhile\\b|\\bswitch\\b|==|!=|<=|>=|\\.class\\b|::|->|\\{\\s*\\n|;\\s*\\n)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXCLUDED_CONTEXT_HINT = Pattern.compile(
            "(obsidian|笔记|日记|知识库|联网|上网|互联网|web|internet|新闻|天气|股价|汇率|日期|时间|今天几号|今天星期几|今天周几|现在几点)",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_HISTORY_MESSAGES = 6;

    private CodingQuestionHelper() {
    }

    public static boolean isCodingImplementationQuestion(String question) {
        return decide(question).isCoding();
    }

    public static boolean isDirectCodingQuestion(String question) {
        return decide(question).isCoding();
    }

    public static boolean isCodeReviewQuestion(String question) {
        return decide(question).type() == CodingIntentType.CODE_REVIEW;
    }

    public static CodingIntentDecision decide(String question) {
        return decide(question, List.of());
    }

    public static CodingIntentDecision decide(String question, List<ChatMessage> history) {
        if (StrUtil.isBlank(question)) {
            return general("blank-question");
        }
        String normalized = StrUtil.trim(question);
        if (EXCLUDED_CONTEXT_HINT.matcher(normalized).find()) {
            return general("excluded-context");
        }
        if (NoteWriteIntentHelper.isLikelyNoteWriteQuestion(normalized)) {
            return general("note-write-question");
        }

        SignalProfile current = analyze(normalized);
        ContextProfile context = analyzeHistory(history);

        if (current.reviewQuestion()) {
            return review(false, "explicit-review");
        }
        if (current.implementationQuestion()) {
            return implementation(false, "explicit-implementation");
        }
        if (current.followupImplementation() && context.programmingContext()) {
            return implementation(true, "follow-up-implementation");
        }
        if (current.followupReview() && context.programmingContext()) {
            return review(true, "follow-up-review");
        }
        if (current.looksLikeCodeSnippet()) {
            if (context.preferredType() == CodingIntentType.CODE_IMPLEMENTATION) {
                return implementation(true, "snippet-inherit-implementation");
            }
            if (context.preferredType() == CodingIntentType.CODE_REVIEW) {
                return review(true, "snippet-inherit-review");
            }
            return review(false, "bare-code-default-review");
        }
        if (current.explicitCodeRequest() && context.programmingContext()) {
            if (context.preferredType() == CodingIntentType.CODE_REVIEW) {
                return review(true, "contextual-review-request");
            }
            return implementation(true, "contextual-code-request");
        }
        return general("no-coding-signal");
    }

    private static SignalProfile analyze(String text) {
        boolean hasProgrammingSemantic = PROGRAMMING_SEMANTIC_HINT.matcher(text).find();
        boolean looksLikeCodeSnippet = CODE_SNIPPET_HINT.matcher(text).find();
        boolean implementationHint = IMPLEMENTATION_HINT.matcher(text).find();
        boolean followupImplementation = IMPLEMENTATION_FOLLOWUP_HINT.matcher(text).find();
        boolean reviewHint = REVIEW_HINT.matcher(text).find();
        boolean followupReview = REVIEW_FOLLOWUP_HINT.matcher(text).find();
        return new SignalProfile(
                hasProgrammingSemantic,
                looksLikeCodeSnippet,
                implementationHint && (hasProgrammingSemantic || looksLikeCodeSnippet),
                reviewHint && (hasProgrammingSemantic || looksLikeCodeSnippet),
                followupImplementation,
                followupReview,
                implementationHint || followupImplementation
        );
    }

    private static ContextProfile analyzeHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return new ContextProfile(false, CodingIntentType.GENERAL);
        }
        List<ChatMessage> recent = new ArrayList<>();
        for (ChatMessage message : history) {
            if (message == null || message.getRole() == ChatMessage.Role.SYSTEM || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            recent.add(message);
        }
        if (recent.isEmpty()) {
            return new ContextProfile(false, CodingIntentType.GENERAL);
        }
        Collections.reverse(recent);
        int upperBound = Math.min(MAX_HISTORY_MESSAGES, recent.size());
        boolean programmingContext = false;
        CodingIntentType preferredType = CodingIntentType.GENERAL;
        for (int i = 0; i < upperBound; i++) {
            SignalProfile signal = analyze(StrUtil.trim(recent.get(i).getContent()));
            programmingContext = programmingContext
                    || signal.hasProgrammingSemantic()
                    || signal.looksLikeCodeSnippet()
                    || signal.implementationQuestion()
                    || signal.reviewQuestion();
            if (preferredType == CodingIntentType.GENERAL) {
                if (signal.reviewQuestion() || signal.followupReview()) {
                    preferredType = CodingIntentType.CODE_REVIEW;
                } else if (signal.implementationQuestion() || signal.followupImplementation()) {
                    preferredType = CodingIntentType.CODE_IMPLEMENTATION;
                } else if (signal.looksLikeCodeSnippet()) {
                    preferredType = CodingIntentType.CODE_REVIEW;
                }
            }
        }
        return new ContextProfile(programmingContext, preferredType);
    }

    private static CodingIntentDecision implementation(boolean inheritedFromContext, String reason) {
        return new CodingIntentDecision(CodingIntentType.CODE_IMPLEMENTATION, inheritedFromContext, reason);
    }

    private static CodingIntentDecision review(boolean inheritedFromContext, String reason) {
        return new CodingIntentDecision(CodingIntentType.CODE_REVIEW, inheritedFromContext, reason);
    }

    private static CodingIntentDecision general(String reason) {
        return new CodingIntentDecision(CodingIntentType.GENERAL, false, reason);
    }

    private record SignalProfile(boolean hasProgrammingSemantic,
                                 boolean looksLikeCodeSnippet,
                                 boolean implementationQuestion,
                                 boolean reviewQuestion,
                                 boolean followupImplementation,
                                 boolean followupReview,
                                 boolean explicitCodeRequest) {
    }

    private record ContextProfile(boolean programmingContext, CodingIntentType preferredType) {
    }
}
