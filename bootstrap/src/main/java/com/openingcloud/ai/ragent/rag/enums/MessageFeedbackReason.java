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

package com.openingcloud.ai.ragent.rag.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 助手回答差评原因枚举
 */
@Getter
@RequiredArgsConstructor
public enum MessageFeedbackReason {

    ROUTING_MISCLASSIFIED("路由判别错误"),

    RETRIEVAL_MISMATCH("检索材料不对"),

    MISSING_WEB_SEARCH("应该联网搜索但没搜"),

    OVERCONFIDENT_CONCLUSION("结论过于武断"),

    IRRELEVANT_RESPONSE("回答与问题无关"),

    OTHER("其他");

    private static final Map<String, MessageFeedbackReason> VALUE_INDEX = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    item -> item.value.toLowerCase(Locale.ROOT),
                    Function.identity()
            ));

    private final String value;

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        MessageFeedbackReason reason = VALUE_INDEX.get(value.trim().toLowerCase(Locale.ROOT));
        return reason == null ? null : reason.getValue();
    }

    public static boolean isSupported(String value) {
        return normalize(value) != null;
    }
}
