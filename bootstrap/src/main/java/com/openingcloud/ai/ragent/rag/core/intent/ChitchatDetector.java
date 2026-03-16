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

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 闲聊快速检测器（规则层）。
 *
 * <p>在任何 LLM 调用之前执行，微秒级判断用户消息是否为闲聊。
 * 命中后可跳过 Query 改写、意图识别和知识库检索，直接由模型回复。
 *
 * <p>设计原则：<strong>宁可漏判，不可误判</strong>。
 * 只对"短消息 + 精确关键词匹配"的高置信度场景返回 true，
 * 避免将包含知识问题的消息误判为闲聊。
 */
public final class ChitchatDetector {

    private static final int MAX_CHITCHAT_LENGTH = 15;

    private static final Set<String> CHITCHAT_KEYWORDS_ZH = Set.of(
            "你好", "您好", "嗨", "嗯", "嗯嗯", "哈哈", "哈哈哈",
            "谢谢", "感谢", "多谢", "谢了", "辛苦了", "辛苦",
            "好的", "好", "收到", "了解", "明白", "知道了",
            "再见", "拜拜", "拜", "晚安", "早安", "早上好", "下午好", "晚上好",
            "在吗", "在么", "在不在",
            "666", "厉害", "牛", "赞", "棒",
            "没事了", "没问题", "算了", "不用了"
    );

    private static final Set<String> CHITCHAT_KEYWORDS_EN = Set.of(
            "hi", "hello", "hey", "yo",
            "thanks", "thank you", "thx", "ty",
            "bye", "goodbye",
            "ok", "okay", "sure", "yes", "no", "yep", "nope",
            "cool", "nice", "great", "good", "fine",
            "lol", "haha"
    );

    private static final Pattern PURE_EMOJI_OR_PUNCTUATION = Pattern.compile(
            "^[\\p{So}\\p{Sk}\\p{Punct}\\s]+$"
    );

    private ChitchatDetector() {
    }

    /**
     * 判断用户消息是否为闲聊。
     *
     * @param question 用户原始消息
     * @return true 表示高置信度闲聊，可跳过 RAG 检索
     */
    public static boolean isChitchat(String question) {
        if (StrUtil.isBlank(question)) {
            return true;
        }

        String trimmed = question.trim();

        if (trimmed.length() > MAX_CHITCHAT_LENGTH) {
            return false;
        }

        if (PURE_EMOJI_OR_PUNCTUATION.matcher(trimmed).matches()) {
            return true;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);

        return CHITCHAT_KEYWORDS_ZH.contains(normalized)
                || CHITCHAT_KEYWORDS_EN.contains(normalized);
    }
}
