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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAIStyleSseParser 单元测试
 * 验证 reasoningEnabled 参数对 reasoning_content 解析的控制行为
 */
class OpenAIStyleSseParserTests {

    private final Gson gson = new Gson();

    // ─── reasoningEnabled=true 场景 ───

    @Test
    @DisplayName("reasoningEnabled=true：应解析 reasoning_content")
    void parseLine_reasoningEnabled_shouldExtractReasoning() {
        String line = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"让我思考一下\",\"content\":\"\"}}]}";

        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, true);

        assertTrue(event.hasReasoning());
        assertEquals("让我思考一下", event.reasoning());
        assertFalse(event.hasContent());
    }

    @Test
    @DisplayName("reasoningEnabled=true：同时包含 content 和 reasoning")
    void parseLine_reasoningEnabled_bothFields() {
        String line = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"分析中\",\"content\":\"回答内容\"}}]}";

        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, true);

        assertTrue(event.hasReasoning());
        assertEquals("分析中", event.reasoning());
        assertTrue(event.hasContent());
        assertEquals("回答内容", event.content());
    }

    // ─── reasoningEnabled=false 场景（bug 修复核心验证）───

    @Test
    @DisplayName("reasoningEnabled=false：应忽略 reasoning_content（bug 修复验证）")
    void parseLine_reasoningDisabled_shouldIgnoreReasoning() {
        String line = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"不应被解析\",\"content\":\"正常回答\"}}]}";

        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, false);

        assertFalse(event.hasReasoning(), "reasoningEnabled=false 时 reasoning 应为 null");
        assertNull(event.reasoning());
        assertTrue(event.hasContent());
        assertEquals("正常回答", event.content());
    }

    @Test
    @DisplayName("reasoningEnabled=false：即使只有 reasoning_content 也应忽略")
    void parseLine_reasoningDisabled_onlyReasoning_shouldReturnEmpty() {
        String line = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"这段推理不应出现\"}}]}";

        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, false);

        assertFalse(event.hasReasoning());
        assertFalse(event.hasContent());
    }

    // ─── 通用解析场景 ───

    @Test
    @DisplayName("解析普通 content delta")
    void parseLine_normalContent() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":\"你好世界\"}}]}";

        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, false);

        assertTrue(event.hasContent());
        assertEquals("你好世界", event.content());
        assertFalse(event.completed());
    }

    @Test
    @DisplayName("解析 [DONE] 标记")
    void parseLine_doneMarker() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine("data: [DONE]", gson, false);

        assertTrue(event.completed());
        assertFalse(event.hasContent());
        assertFalse(event.hasReasoning());
    }

    @Test
    @DisplayName("解析 finish_reason 标记完成")
    void parseLine_finishReason() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}";

        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, false);

        assertTrue(event.completed());
    }

    @Test
    @DisplayName("空行和 null 返回空事件")
    void parseLine_emptyInput() {
        assertFalse(OpenAIStyleSseParser.parseLine(null, gson, false).hasContent());
        assertFalse(OpenAIStyleSseParser.parseLine("", gson, false).hasContent());
        assertFalse(OpenAIStyleSseParser.parseLine("   ", gson, false).hasContent());
    }

    @Test
    @DisplayName("message 格式也能正确解析（非 delta）")
    void parseLine_messageFormat() {
        String line = "data: {\"choices\":[{\"message\":{\"content\":\"完整回答\",\"reasoning_content\":\"完整推理\"}}]}";

        OpenAIStyleSseParser.ParsedEvent eventEnabled = OpenAIStyleSseParser.parseLine(line, gson, true);
        assertTrue(eventEnabled.hasContent());
        assertTrue(eventEnabled.hasReasoning());
        assertEquals("完整回答", eventEnabled.content());
        assertEquals("完整推理", eventEnabled.reasoning());

        OpenAIStyleSseParser.ParsedEvent eventDisabled = OpenAIStyleSseParser.parseLine(line, gson, false);
        assertTrue(eventDisabled.hasContent());
        assertFalse(eventDisabled.hasReasoning(), "reasoningEnabled=false 应忽略 message 格式中的 reasoning");
    }
}
