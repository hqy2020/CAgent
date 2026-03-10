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

package com.openingcloud.ai.ragent.rag.trace;

import com.openingcloud.ai.ragent.framework.trace.RagTraceNode;
import com.openingcloud.ai.ragent.framework.trace.RagTraceRoot;
import com.openingcloud.ai.ragent.infra.chat.RoutingLLMService;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.chat.StreamCallback;
import com.openingcloud.ai.ragent.rag.aop.ChatRateLimit;
import com.openingcloud.ai.ragent.rag.controller.RAGChatController;
import com.openingcloud.ai.ragent.rag.core.cancel.CancellationToken;
import com.openingcloud.ai.ragent.rag.core.intent.IntentResolver;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPServiceOrchestrator;
import com.openingcloud.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.openingcloud.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.openingcloud.ai.ragent.rag.core.rewrite.RewriteResult;
import com.openingcloud.ai.ragent.rag.service.impl.RAGChatServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("RAG 全链路 Trace 阶段契约")
class TraceStageContractTests {

    @Test
    @DisplayName("重写阶段：history 版本方法必须有 Trace 注解")
    void rewriteStageShouldHaveTraceAnnotation() throws NoSuchMethodException {
        Method method = MultiQuestionRewriteService.class.getMethod("rewriteWithSplit", String.class, List.class);
        RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);
        assertNotNull(annotation);
        assertEquals("query-rewrite-and-split", annotation.name());
        assertEquals("REWRITE", annotation.type());
    }

    @Test
    @DisplayName("意图阶段：token 版本方法必须有 Trace 注解")
    void intentStageShouldHaveTraceAnnotationOnTokenMethod() throws NoSuchMethodException {
        Method method = IntentResolver.class.getMethod("resolve", RewriteResult.class, CancellationToken.class);
        RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);
        assertNotNull(annotation);
        assertEquals("intent-resolve", annotation.name());
        assertEquals("INTENT", annotation.type());
    }

    @Test
    @DisplayName("检索阶段：token 版本方法必须有 Trace 注解")
    void retrievalStageShouldHaveTraceAnnotationOnTokenMethod() throws NoSuchMethodException {
        Method method = RetrievalEngine.class.getMethod("retrieve", List.class, int.class, CancellationToken.class);
        RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);
        assertNotNull(annotation);
        assertEquals("retrieval-engine", annotation.name());
        assertEquals("RETRIEVE", annotation.type());
    }

    @Test
    @DisplayName("生成阶段：LLM 流式路由方法必须有 Trace 注解")
    void generationStageShouldHaveTraceAnnotation() throws NoSuchMethodException {
        Method method = RoutingLLMService.class.getMethod("streamChat", ChatRequest.class, StreamCallback.class);
        RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);
        assertNotNull(annotation);
        assertEquals("llm-stream-routing", annotation.name());
        assertEquals("LLM_ROUTING", annotation.type());
    }

    @Test
    @DisplayName("MCP 阶段：可取消批量执行方法必须有 Trace 注解")
    void mcpStageShouldHaveTraceAnnotationOnCancellableBatchMethod() throws NoSuchMethodException {
        Method method = MCPServiceOrchestrator.class.getMethod("executeBatch", List.class, CancellationToken.class);
        RagTraceNode annotation = method.getAnnotation(RagTraceNode.class);
        assertNotNull(annotation);
        assertEquals("mcp-execute-batch-cancellable", annotation.name());
        assertEquals("MCP", annotation.type());
    }

    @Test
    @DisplayName("SSE chat 入口不应重复声明 RagTraceRoot（避免重复 run）")
    void chatControllerShouldNotDeclareRagTraceRoot() throws NoSuchMethodException {
        Method method = RAGChatController.class.getMethod("chat", String.class, String.class, Boolean.class);
        RagTraceRoot annotation = method.getAnnotation(RagTraceRoot.class);
        assertNull(annotation);
    }

    @Test
    @DisplayName("SSE chat 服务入口应使用 ChatRateLimit 切面作为 trace root")
    void chatServiceShouldUseChatRateLimitAnnotation() throws NoSuchMethodException {
        Method method = RAGChatServiceImpl.class.getMethod(
                "streamChat",
                String.class,
                String.class,
                Boolean.class,
                SseEmitter.class
        );
        ChatRateLimit annotation = method.getAnnotation(ChatRateLimit.class);
        assertNotNull(annotation);
    }
}
