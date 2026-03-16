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

package com.openingcloud.ai.ragent.rag.core.usermemory;

import java.util.regex.Pattern;

/**
 * 检测用户消息中"记住/记下/保存"等显式记忆保存指令
 * 使用正则匹配，零延迟、确定性高
 */
public class PinnedMemoryDetector {

    private static final Pattern PINNED_PATTERN = Pattern.compile(
            "^(记住|记下|保存|帮我记|请记住|请记下|请保存|记一下|帮我记一下)[:：]?\\s*(.+)",
            Pattern.DOTALL
    );

    /**
     * 检测是否为记忆保存指令
     *
     * @param message 用户消息
     * @return 匹配结果，null 表示不匹配
     */
    public static PinnedDetectResult detect(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        var matcher = PINNED_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            String content = matcher.group(2).trim();
            if (!content.isEmpty()) {
                return new PinnedDetectResult(content);
            }
        }
        return null;
    }

    public record PinnedDetectResult(String content) {
    }
}
