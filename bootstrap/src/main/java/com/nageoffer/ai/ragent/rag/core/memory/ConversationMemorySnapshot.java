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

package com.nageoffer.ai.ragent.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.convention.ChatMessage;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆快照，只承载原始材料：
 * 最新摘要 + 最近若干轮原始消息。
 */
@Data
@Builder
public class ConversationMemorySnapshot {

    @Builder.Default
    private List<ChatMessage> recentHistory = List.of();

    private ChatMessage summary;

    public static ConversationMemorySnapshot empty() {
        return ConversationMemorySnapshot.builder().recentHistory(List.of()).build();
    }

    public boolean hasSummary() {
        return summary != null && StrUtil.isNotBlank(summary.getContent());
    }

    public List<ChatMessage> toHistoryMessages() {
        if (!hasSummary() && CollUtil.isEmpty(recentHistory)) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>();
        if (hasSummary()) {
            result.add(summary);
        }
        if (CollUtil.isNotEmpty(recentHistory)) {
            result.addAll(recentHistory);
        }
        return result;
    }
}
