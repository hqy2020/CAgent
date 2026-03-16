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

package com.openingcloud.ai.ragent.rag.rewrite;

import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.infra.token.TokenCounterService;
import com.openingcloud.ai.ragent.rag.config.RAGConfigProperties;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.openingcloud.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.openingcloud.ai.ragent.rag.core.rewrite.RewriteResult;
import com.openingcloud.ai.ragent.framework.trace.RagTraceNode;
import com.openingcloud.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("问题重写与拆分 Bug 修复验证")
class QueryRewriteSplitTests {

    @Mock
    private LLMService llmService;

    @Mock
    private RAGConfigProperties ragConfigProperties;

    @Mock
    private QueryTermMappingService queryTermMappingService;

    @Mock
    private PromptTemplateLoader promptTemplateLoader;
    @Mock
    private TokenCounterService tokenCounterService;
    @Mock
    private RagTraceRecordService ragTraceRecordService;

    @InjectMocks
    private MultiQuestionRewriteService rewriteService;

    @BeforeEach
    void setUp() {
        lenient().when(ragConfigProperties.getQueryRewriteEnabled()).thenReturn(true);
        lenient().when(ragConfigProperties.getQueryRewriteMaxHistoryMessages()).thenReturn(4);
        lenient().when(ragConfigProperties.getQueryRewriteMaxHistoryTokens()).thenReturn(600);
        lenient().when(ragConfigProperties.getQueryRewriteMaxHistoryChars()).thenReturn(500);
        lenient().when(queryTermMappingService.normalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(promptTemplateLoader.load(anyString())).thenReturn("system prompt");
        lenient().when(tokenCounterService.countTokens(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text == null ? 0 : Math.max(1, text.length() / 4);
        });
    }

    // ────────────────────────────────────────
    // Fix 1: 历史截断基于过滤后 size
    // ────────────────────────────────────────

    @Nested
    @DisplayName("Fix 1 - 历史截断逻辑")
    class HistoryTruncationTests {

        @Test
        @DisplayName("包含 SYSTEM 消息时，截断数量基于过滤后列表长度")
        void shouldTruncateBasedOnFilteredSize() {
            // history: [SYSTEM, USER, ASST, USER, ASST, USER, ASST] = 7条
            // 过滤后: [USER, ASST, USER, ASST, USER, ASST] = 6条
            // maxMessages=4 -> 保留最后 4 条
            List<ChatMessage> history = List.of(
                    ChatMessage.system("这是会话摘要"),
                    ChatMessage.user("第一个问题"),
                    ChatMessage.assistant("第一个回答"),
                    ChatMessage.user("第二个问题"),
                    ChatMessage.assistant("第二个回答"),
                    ChatMessage.user("第三个问题"),
                    ChatMessage.assistant("第三个回答")
            );

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试问题\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("新问题", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            ChatRequest req = captor.getValue();

            // messages = [system_prompt] + [4条历史] + [user_question] = 6条
            List<ChatMessage> messages = req.getMessages();
            // 系统提示 + 最多4条历史 + 当前问题
            long historyCount = messages.stream()
                    .filter(m -> m.getRole() == ChatMessage.Role.USER || m.getRole() == ChatMessage.Role.ASSISTANT)
                    .count();
            // 4 条历史 + 1 条当前用户问题 = 5
            assertEquals(5, historyCount, "应保留4条历史消息 + 1条当前问题");

            // 验证 SYSTEM 摘要消息没有被传递（只有 system prompt）
            long systemCount = messages.stream()
                    .filter(m -> m.getRole() == ChatMessage.Role.SYSTEM)
                    .count();
            assertEquals(1, systemCount, "只应有1条系统提示词，SYSTEM摘要应被过滤");
        }

        @Test
        @DisplayName("maxHistoryMessages 配置项生效")
        void shouldRespectMaxHistoryMessagesConfig() {
            when(ragConfigProperties.getQueryRewriteMaxHistoryMessages()).thenReturn(2);

            List<ChatMessage> history = List.of(
                    ChatMessage.user("问题1"),
                    ChatMessage.assistant("回答1"),
                    ChatMessage.user("问题2"),
                    ChatMessage.assistant("回答2"),
                    ChatMessage.user("问题3"),
                    ChatMessage.assistant("回答3")
            );

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("新问题", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            List<ChatMessage> messages = captor.getValue().getMessages();

            // system_prompt + 2条历史 + 当前问题 = 4
            assertEquals(4, messages.size(), "maxHistoryMessages=2 时，总消息数应为4");
        }

        @Test
        @DisplayName("maxHistoryChars 字符截断生效")
        void shouldRespectMaxHistoryCharsConfig() {
            when(ragConfigProperties.getQueryRewriteMaxHistoryChars()).thenReturn(50);

            // 每条消息约 20 字符，4 条 = 80 字符 > 50 限制
            List<ChatMessage> history = List.of(
                    ChatMessage.user("这是一个二十字符左右的问题一"),      // ~14 chars
                    ChatMessage.assistant("这是一个二十字符左右的回答一"),  // ~14 chars
                    ChatMessage.user("这是一个二十字符左右的问题二"),      // ~14 chars
                    ChatMessage.assistant("这是一个二十字符左右的回答二")   // ~14 chars
            );

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("当前问题", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            List<ChatMessage> messages = captor.getValue().getMessages();

            // 字符限制应该导致保留的历史消息少于 4 条
            long historyMsgCount = messages.stream()
                    .filter(m -> m.getRole() != ChatMessage.Role.SYSTEM)
                    .count() - 1; // 减去当前用户问题
            assertTrue(historyMsgCount < 4, "字符限制应截断部分历史消息");
        }

        @Test
        @DisplayName("BUG 1: history 含 null content 时不应抛 NPE")
        void shouldNotThrowNPEWhenHistoryContainsNullContent() {
            List<ChatMessage> history = new ArrayList<>();
            history.add(ChatMessage.user("正常问题"));
            history.add(ChatMessage.assistant(null)); // null content
            history.add(ChatMessage.user("第二个问题"));
            history.add(ChatMessage.assistant("正常回答"));

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试\", \"should_split\": false}");

            assertDoesNotThrow(() -> rewriteService.rewriteWithSplit("新问题", history),
                    "history 含 null content 时不应抛 NPE");
        }

        @Test
        @DisplayName("BUG 5: blank content 消息应被过滤")
        void shouldFilterBlankContentMessages() {
            List<ChatMessage> history = new ArrayList<>();
            history.add(ChatMessage.user("正常问题"));
            history.add(ChatMessage.assistant(""));  // blank content
            history.add(ChatMessage.user("  "));     // whitespace-only content
            history.add(ChatMessage.assistant("正常回答"));

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("新问题", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            List<ChatMessage> messages = captor.getValue().getMessages();

            // blank content 消息应被过滤掉，只保留 system_prompt + 2条有效历史 + 当前问题
            long nonSystemCount = messages.stream()
                    .filter(m -> m.getRole() != ChatMessage.Role.SYSTEM)
                    .count();
            assertEquals(3, nonSystemCount, "应过滤掉 blank content 消息，保留2条有效历史 + 1条当前问题");
        }

        @Test
        @DisplayName("摘要中的老实体应参与改写上下文")
        void shouldKeepSummaryWhenOnlySummaryContainsEntity() {
            List<ChatMessage> history = List.of(
                    ChatMessage.system("对话摘要：历史讨论：用户咨询了 iPhone 16 Pro 保修政策（已解答）。实体：iPhone 16 Pro。"),
                    ChatMessage.user("最近在聊手机售后")
            );

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"iPhone 16 Pro 的保修期是什么\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("那它的保修期呢？", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            List<ChatMessage> messages = captor.getValue().getMessages();

            long systemCount = messages.stream()
                    .filter(m -> m.getRole() == ChatMessage.Role.SYSTEM)
                    .count();
            assertEquals(2, systemCount, "应同时包含系统提示词和摘要系统消息");
            assertTrue(messages.stream().anyMatch(m -> "对话摘要：历史讨论：用户咨询了 iPhone 16 Pro 保修政策（已解答）。实体：iPhone 16 Pro。".equals(m.getContent())));
        }
    }

    // ────────────────────────────────────────
    // Fix 3: should_split 字段被尊重
    // ────────────────────────────────────────

    @Nested
    @DisplayName("BUG 2 - 单条超长消息不应丢弃全部历史")
    class LongSingleMessageTests {

        @Test
        @DisplayName("单条消息超过 maxChars 时至少保留最近 1 条")
        void shouldKeepAtLeastOneMessageWhenSingleMessageExceedsMaxChars() {
            when(ragConfigProperties.getQueryRewriteMaxHistoryChars()).thenReturn(50);

            // 构造一条远超 50 字符的消息
            String longContent = "这是一段非常非常长的用户问题内容，远远超过了默认的字符限制，".repeat(5);
            List<ChatMessage> history = List.of(
                    ChatMessage.user(longContent)
            );

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("它是什么意思", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            List<ChatMessage> messages = captor.getValue().getMessages();

            // system_prompt + 至少1条历史 + 当前问题 >= 3
            long historyCount = messages.stream()
                    .filter(m -> m.getRole() == ChatMessage.Role.USER || m.getRole() == ChatMessage.Role.ASSISTANT)
                    .count();
            assertTrue(historyCount >= 2, "应至少保留1条历史消息 + 1条当前问题");
        }

        @Test
        @DisplayName("全部消息超长时保留最近 1 条")
        void shouldKeepLastMessageWhenAllMessagesAreLong() {
            when(ragConfigProperties.getQueryRewriteMaxHistoryChars()).thenReturn(50);

            String longMsg = "A".repeat(600);
            List<ChatMessage> history = List.of(
                    ChatMessage.user(longMsg),
                    ChatMessage.assistant(longMsg),
                    ChatMessage.user(longMsg),
                    ChatMessage.assistant(longMsg)
            );

            when(llmService.chat(any(ChatRequest.class))).thenReturn(
                    "{\"rewrite\": \"测试\", \"should_split\": false}");

            rewriteService.rewriteWithSplit("后续问题", history);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(llmService).chat(captor.capture());
            List<ChatMessage> messages = captor.getValue().getMessages();

            // 至少应包含: system_prompt + 1条历史(最近的) + 当前问题
            long nonSystemCount = messages.stream()
                    .filter(m -> m.getRole() != ChatMessage.Role.SYSTEM)
                    .count();
            assertTrue(nonSystemCount >= 2, "全部超长时至少保留最近1条历史 + 1条当前问题");
        }
    }

    // ────────────────────────────────────────
    // BUG 4: @RagTraceNode 注解验证
    // ────────────────────────────────────────

    @Nested
    @DisplayName("BUG 4 - @RagTraceNode 注解完整性")
    class TraceAnnotationTests {

        @Test
        @DisplayName("rewriteWithSplit(String) 应有 @RagTraceNode 注解")
        void rewriteWithSplitShouldHaveTraceAnnotation() throws NoSuchMethodException {
            Method method = MultiQuestionRewriteService.class.getMethod("rewriteWithSplit", String.class);
            RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);

            assertNotNull(annotation, "rewriteWithSplit(String) 缺少 @RagTraceNode 注解");
            assertEquals("query-rewrite-and-split", annotation.name());
            assertEquals("REWRITE", annotation.type());
        }

        @Test
        @DisplayName("所有公开 rewrite 方法均有 @RagTraceNode 注解")
        void allPublicRewriteMethodsShouldHaveTraceAnnotation() {
            for (Method method : MultiQuestionRewriteService.class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        && (method.getName().equals("rewrite") || method.getName().equals("rewriteWithSplit"))) {
                    RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);
                    assertNotNull(annotation,
                            "公开方法 " + method.getName() + "(" + method.getParameterCount() + " params) 缺少 @RagTraceNode 注解");
                }
            }
        }
    }

    // ────────────────────────────────────────

    @Nested
    @DisplayName("Fix 3 - should_split 尊重")
    class ShouldSplitTests {

        private final List<ChatMessage> dummyHistory = List.of(
                ChatMessage.user("前一个问题"), ChatMessage.assistant("前一个回答"));

        @Test
        @DisplayName("should_split=false 时即使有 sub_questions 也不拆分")
        void shouldNotSplitWhenShouldSplitIsFalse() {
            String llmResponse = """
                    {
                      "rewrite": "微服务和单体架构有什么区别",
                      "should_split": false,
                      "sub_questions": ["微服务的优点是什么", "单体架构的优点是什么"]
                    }
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            RewriteResult result = rewriteService.rewriteWithSplit("微服务和单体有什么区别？", dummyHistory);

            assertEquals(1, result.subQuestions().size(),
                    "should_split=false 时子问题应只有改写后的问题本身");
            assertEquals("微服务和单体架构有什么区别", result.subQuestions().get(0));
        }

        @Test
        @DisplayName("should_split=true 时正常拆分")
        void shouldSplitWhenShouldSplitIsTrue() {
            String llmResponse = """
                    {
                      "rewrite": "Obsidian 双向链接和知识库检索能力",
                      "should_split": true,
                      "sub_questions": ["Obsidian 的双向链接怎么用", "知识库检索有哪些能力"]
                    }
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            RewriteResult result = rewriteService.rewriteWithSplit("Obsidian 双向链接怎么用？知识库有哪些检索能力？", dummyHistory);

            assertEquals(2, result.subQuestions().size(), "should_split=true 时应拆分为2个子问题");
        }

        @Test
        @DisplayName("没有 should_split 字段时默认不拆分")
        void shouldNotSplitWhenFieldMissing() {
            String llmResponse = """
                    {
                      "rewrite": "12306系统的架构设计",
                      "sub_questions": ["12306的前端架构", "12306的后端架构"]
                    }
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            RewriteResult result = rewriteService.rewriteWithSplit("12306架构怎么做的？", dummyHistory);

            assertEquals(1, result.subQuestions().size(),
                    "缺少 should_split 字段时应默认不拆分");
        }
    }

    // ────────────────────────────────────────
    // Fix 6: ruleBasedSplit 智能标点
    // ────────────────────────────────────────

    @Nested
    @DisplayName("新格式 {queries} 解析")
    class NewQueriesFormatTests {

        private final List<ChatMessage> dummyHistory = List.of(
                ChatMessage.user("前一个问题"), ChatMessage.assistant("前一个回答"));

        @Test
        @DisplayName("单查询新格式正常解析")
        void shouldParseSingleQueryNewFormat() {
            String llmResponse = """
                    {
                      "queries": ["RAG 检索流程总结"]
                    }
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            RewriteResult result = rewriteService.rewriteWithSplit("请帮我总结 RAG 检索流程", dummyHistory);

            assertEquals("请帮我总结 RAG 检索流程", result.originalQuestion());
            assertEquals("RAG 检索流程总结", result.rewrittenQuestion());
            assertEquals(1, result.subQuestions().size());
            assertEquals("RAG 检索流程总结", result.subQuestions().get(0));
        }

        @Test
        @DisplayName("多查询新格式正常拆分")
        void shouldParseMultiQueryNewFormat() {
            String llmResponse = """
                    {
                      "queries": ["HashMap 的底层原理是什么", "ConcurrentHashMap 有什么区别"]
                    }
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            RewriteResult result = rewriteService.rewriteWithSplit("HashMap 原理？ConcurrentHashMap 区别？", dummyHistory);

            assertEquals("HashMap 原理？ConcurrentHashMap 区别？", result.originalQuestion());
            assertEquals("HashMap 的底层原理是什么", result.rewrittenQuestion());
            assertEquals(2, result.subQuestions().size());
        }

        @Test
        @DisplayName("originalQuestion 始终保留用户原始问题")
        void shouldPreserveOriginalQuestion() {
            String llmResponse = """
                    {
                      "queries": ["RAG 系统的检索链路"]
                    }
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            RewriteResult result = rewriteService.rewriteWithSplit("它的检索链路怎么走？",
                    List.of(ChatMessage.user("RAG 系统的架构是什么"), ChatMessage.assistant("...")));

            assertEquals("它的检索链路怎么走？", result.originalQuestion());
            assertEquals("RAG 系统的检索链路", result.rewrittenQuestion());
        }
    }

    @Nested
    @DisplayName("首轮跳过优化")
    class FirstRoundSkipTests {

        @Test
        @DisplayName("首轮无历史且无指代词时跳过 LLM 调用")
        void shouldSkipLLMWhenNoHistoryAndNoPronouns() {
            RewriteResult result = rewriteService.rewriteWithSplit("RAG 系统的架构是什么", List.of());

            assertEquals("RAG 系统的架构是什么", result.originalQuestion());
            verify(llmService, never()).chat(any(ChatRequest.class));
        }

        @Test
        @DisplayName("含指代词时不跳过 LLM")
        void shouldNotSkipWhenPronounPresent() {
            String llmResponse = """
                    {"queries": ["RAG 系统的检索链路"]}
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            rewriteService.rewriteWithSplit("它的检索链路怎么走", List.of());

            verify(llmService).chat(any(ChatRequest.class));
        }

        @Test
        @DisplayName("有历史时不跳过 LLM")
        void shouldNotSkipWhenHistoryPresent() {
            String llmResponse = """
                    {"queries": ["RAG 检索流程"]}
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            rewriteService.rewriteWithSplit("RAG 检索流程是什么",
                    List.of(ChatMessage.user("上一个问题"), ChatMessage.assistant("上一个回答")));

            verify(llmService).chat(any(ChatRequest.class));
        }

        @Test
        @DisplayName("问题太短时不跳过 LLM")
        void shouldNotSkipWhenQuestionTooShort() {
            String llmResponse = """
                    {"queries": ["RAG"]}
                    """;
            when(llmService.chat(any(ChatRequest.class))).thenReturn(llmResponse);

            rewriteService.rewriteWithSplit("RAG", List.of());

            verify(llmService).chat(any(ChatRequest.class));
        }
    }

    @Nested
    @DisplayName("Fix 6 - ruleBasedSplit 智能标点")
    class RuleBasedSplitTests {

        @BeforeEach
        void disableLLM() {
            when(ragConfigProperties.getQueryRewriteEnabled()).thenReturn(false);
        }

        @Test
        @DisplayName("疑问句应追加问号")
        void shouldAppendQuestionMarkForQuestions() {
            RewriteResult result = rewriteService.rewriteWithSplit("周复盘模板怎么写");

            assertTrue(result.subQuestions().get(0).endsWith("？"),
                    "疑问句应追加中文问号");
        }

        @Test
        @DisplayName("陈述句不应追加问号")
        void shouldNotAppendQuestionMarkForStatements() {
            RewriteResult result = rewriteService.rewriteWithSplit("系统部署在阿里云上。Redis端口是6379");

            List<String> subs = result.subQuestions();
            assertEquals(2, subs.size());
            // "系统部署在阿里云上" 是陈述句，不含疑问词
            assertFalse(subs.get(0).endsWith("？"), "陈述句不应追加问号");
        }

        @Test
        @DisplayName("混合场景：疑问句加问号，陈述句不加")
        void shouldHandleMixedSentences() {
            RewriteResult result = rewriteService.rewriteWithSplit("Obsidian 双向链接怎么用？知识库部署在本地服务器上");

            List<String> subs = result.subQuestions();
            assertEquals(2, subs.size());
            assertTrue(subs.get(0).contains("怎么"), "第一个应是疑问句");
            assertFalse(subs.get(1).endsWith("？"), "第二个是陈述句，不应追加问号");
        }
    }

    // ────────────────────────────────────────
    // LLM 异常降级
    // ────────────────────────────────────────

    @Nested
    @DisplayName("LLM 异常降级")
    class FallbackTests {

        private final List<ChatMessage> dummyHistory = List.of(
                ChatMessage.user("前一个问题"), ChatMessage.assistant("前一个回答"));

        @Test
        @DisplayName("LLM 超时时降级为归一化问题")
        void shouldFallbackWhenLLMTimeout() {
            when(llmService.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException("timeout"));

            RewriteResult result = rewriteService.rewriteWithSplit("测试问题", dummyHistory);

            assertNotNull(result);
            assertEquals("测试问题", result.originalQuestion());
            assertEquals("测试问题", result.rewrittenQuestion());
            assertEquals(List.of("测试问题"), result.subQuestions());
        }

        @Test
        @DisplayName("LLM 返回非法 JSON 时降级")
        void shouldFallbackWhenInvalidJson() {
            when(llmService.chat(any(ChatRequest.class))).thenReturn("这不是JSON");

            RewriteResult result = rewriteService.rewriteWithSplit("测试问题", dummyHistory);

            assertNotNull(result);
            assertEquals("测试问题", result.rewrittenQuestion());
        }
    }

    // ────────────────────────────────────────
    // 规则拆分兜底（queryRewriteEnabled=false）
    // ────────────────────────────────────────

    @Nested
    @DisplayName("规则拆分兜底")
    class RuleBasedFallbackTests {

        @BeforeEach
        void disableLLM() {
            when(ragConfigProperties.getQueryRewriteEnabled()).thenReturn(false);
        }

        @Test
        @DisplayName("开关关闭时按分隔符拆分")
        void shouldSplitByDelimitersWhenDisabled() {
            RewriteResult result = rewriteService.rewriteWithSplit("OA怎么用？CRM呢？");

            assertNotNull(result);
            assertTrue(result.subQuestions().size() >= 2, "应按问号拆分为多个子问题");
        }

        @Test
        @DisplayName("单个问题不拆分")
        void shouldNotSplitSingleQuestion() {
            RewriteResult result = rewriteService.rewriteWithSplit("微服务和单体有什么区别");

            assertEquals(1, result.subQuestions().size(), "单个问题不应被拆分");
        }
    }
}
