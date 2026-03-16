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

import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingQuestionHelperTests {

    @Test
    void shouldRecognizeImplementationQuestion() {
        assertTrue(CodingQuestionHelper.isDirectCodingQuestion("线程交替打印 A 和 B，哪种方法更好"));
    }

    @Test
    void shouldRecognizeCodeReviewQuestion() {
        String question = """
                public class DCL {
                    private static volatile DCL INSTANCE;

                    public static DCL getInstance() {
                        if (INSTANCE == null) {
                            synchronized (DCL.class) {
                                if (INSTANCE == null) {
                                    INSTANCE = new DCL();
                                }
                            }
                        }
                        return INSTANCE;
                    }
                }

                这段 DCL 代码正确吗
                """;
        assertTrue(CodingQuestionHelper.isDirectCodingQuestion(question));
        assertTrue(CodingQuestionHelper.isCodeReviewQuestion(question));
    }

    @Test
    void shouldTreatBareCodeSnippetAsCodeReviewByDefault() {
        CodingQuestionHelper.CodingIntentDecision decision = CodingQuestionHelper.decide("""
                package com.openingcloud.ai.ragent;

                public class DCL {
                    private static volatile DCL INSTANCE;
                    private DCL() {}

                    public static DCL getInstance() {
                        if (INSTANCE == null) {
                            synchronized (DCL.class) {
                                if (INSTANCE == null) {
                                    INSTANCE = new DCL();
                                }
                            }
                        }
                        return INSTANCE;
                    }
                }
                """);

        assertEquals(CodingQuestionHelper.CodingIntentType.CODE_REVIEW, decision.type());
        assertFalse(decision.inheritedFromContext());
    }

    @Test
    void shouldInheritImplementationIntentFromProgrammingContext() {
        CodingQuestionHelper.CodingIntentDecision decision = CodingQuestionHelper.decide(
                "给我具体的代码",
                List.of(
                        ChatMessage.user("一个线程打印 A，一个线程打印 B，最终输出 AABBAABB，用哪个方法实现最方便"),
                        ChatMessage.assistant("可以使用 synchronized、wait/notify 或 ReentrantLock。")
                )
        );

        assertEquals(CodingQuestionHelper.CodingIntentType.CODE_IMPLEMENTATION, decision.type());
        assertTrue(decision.inheritedFromContext());
    }

    @Test
    void shouldKeepGenericCodeRequestAsGeneralWithoutProgrammingContext() {
        CodingQuestionHelper.CodingIntentDecision decision = CodingQuestionHelper.decide("给我具体的代码");

        assertEquals(CodingQuestionHelper.CodingIntentType.GENERAL, decision.type());
    }

    @Test
    void shouldNotTreatObsidianQuestionAsCodingQuestion() {
        assertFalse(CodingQuestionHelper.isDirectCodingQuestion("帮我在 Obsidian 里记录 Java 并发笔记"));
    }

    @Test
    void shouldNotTreatRealtimeQuestionAsCodingQuestion() {
        assertFalse(CodingQuestionHelper.isDirectCodingQuestion("今天上海天气怎么样"));
    }
}
