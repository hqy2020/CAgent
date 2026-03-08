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

package com.nageoffer.ai.ragent.rag.agent;

import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.dto.WorkflowEventPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCommandRouterTests {

    @Mock
    private AgentWorkflowRegistry workflowRegistry;

    @Mock
    private AgentWorkflow workflow;

    @Mock
    private StreamCallback callback;

    @Mock
    private PendingProposalStore pendingProposalStore;

    @Mock
    private MCPService mcpService;

    private AgentCommandRouter router;

    @BeforeEach
    void setUp() {
        router = new AgentCommandRouter(workflowRegistry, pendingProposalStore, mcpService);
    }

    @Test
    void shouldRouteQyReviewAndSendWorkflowEvent() {
        when(workflowRegistry.find(any())).thenReturn(Optional.of(workflow));
        when(workflow.id()).thenReturn("/qy-review");
        when(workflow.execute(any())).thenReturn(AgentWorkflowResult.builder()
                .reply("ok")
                .changedFiles(List.of())
                .opsCount(0)
                .warnings(List.of())
                .build());

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean routed = router.tryRoute(
                "/qy-review smoke",
                "c1",
                "u1",
                "t1",
                emitter,
                callback,
                CancellationToken.NONE
        );

        assertTrue(routed);
        verify(callback).onContent("ok");
        verify(callback).onComplete();
        assertTrue(emitter.rawPayloads.stream().anyMatch(each -> each instanceof WorkflowEventPayload));
    }

    @Test
    void shouldReturnFalseForNonCommandInput() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean routed = router.tryRoute(
                "hello world",
                "c1",
                "u1",
                "t1",
                emitter,
                callback,
                CancellationToken.NONE
        );

        assertFalse(routed);
        verifyNoInteractions(workflowRegistry, callback, workflow);
        assertTrue(emitter.eventNames.isEmpty());
    }

    @Test
    void shouldFallbackWhenWorkflowThrows() {
        when(workflowRegistry.find(any())).thenReturn(Optional.of(workflow));
        when(workflow.id()).thenReturn("/qy-review");
        when(workflow.execute(any())).thenThrow(new RuntimeException("boom"));

        CapturingSseEmitter emitter = new CapturingSseEmitter();
        boolean routed = router.tryRoute(
                "/qy-review crash",
                "c1",
                "u1",
                "t1",
                emitter,
                callback,
                CancellationToken.NONE
        );

        assertFalse(routed);
        verify(callback, never()).onComplete();
        assertTrue(emitter.rawPayloads.isEmpty());
    }

    @Test
    void shouldConfirmProposalAndExecuteMcp() {
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-1")
                .conversationId("c1")
                .userId("u1")
                .toolId("obsidian_update")
                .userQuestion("update")
                .parameters(new HashMap<>())
                .targetPath("a.md")
                .status(PendingProposalStore.STATUS_PENDING)
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 10000)
                .build();
        when(pendingProposalStore.confirm(eq("p-1"), eq("c1"), eq("u1")))
                .thenReturn(new PendingProposalStore.ProposalDecisionResult(true, proposal, null));
        when(mcpService.execute(any())).thenReturn(MCPResponse.success("obsidian_update", "ok"));

        boolean routed = router.tryRoute(
                "/confirm p-1",
                "c1",
                "u1",
                "t1",
                new CapturingSseEmitter(),
                callback,
                CancellationToken.NONE
        );

        assertTrue(routed);
        verify(callback).onContent("ok");
        verify(callback).onComplete();
    }

    @Test
    void shouldMergeParameterOverridesWhenConfirmingProposal() {
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-override")
                .conversationId("c1")
                .userId("u1")
                .toolId("obsidian_create")
                .userQuestion("create note")
                .parameters(new HashMap<>())
                .status(PendingProposalStore.STATUS_PENDING)
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 10000)
                .build();
        when(pendingProposalStore.confirm(eq("p-override"), eq("c1"), eq("u1")))
                .thenReturn(new PendingProposalStore.ProposalDecisionResult(true, proposal, null));
        when(mcpService.execute(any())).thenReturn(MCPResponse.success("obsidian_create", "ok"));

        boolean routed = router.tryRoute(
                "/confirm p-override name=今日日报",
                "c1",
                "u1",
                "t1",
                new CapturingSseEmitter(),
                callback,
                CancellationToken.NONE
        );

        assertTrue(routed);
        ArgumentCaptor<MCPRequest> requestCaptor = ArgumentCaptor.forClass(MCPRequest.class);
        verify(mcpService).execute(requestCaptor.capture());
        assertEquals("今日日报", requestCaptor.getValue().getStringParameter("name"));
    }

    @Test
    void shouldRejectProposal() {
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-2")
                .conversationId("c1")
                .userId("u1")
                .status(PendingProposalStore.STATUS_PENDING)
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 10000)
                .build();
        when(pendingProposalStore.reject(eq("p-2"), eq("c1"), eq("u1")))
                .thenReturn(new PendingProposalStore.ProposalDecisionResult(true, proposal, null));

        boolean routed = router.tryRoute(
                "/reject p-2",
                "c1",
                "u1",
                "t1",
                new CapturingSseEmitter(),
                callback,
                CancellationToken.NONE
        );

        assertTrue(routed);
        verify(callback).onContent("已拒绝提案：p-2");
        verify(callback).onComplete();
    }

    @Test
    void shouldFailWhenConfirmProposalUnauthorized() {
        when(pendingProposalStore.confirm(eq("p-3"), eq("c1"), eq("u1")))
                .thenReturn(new PendingProposalStore.ProposalDecisionResult(false, null, "无权限确认该提案"));

        boolean routed = router.tryRoute(
                "/confirm p-3",
                "c1",
                "u1",
                "t1",
                new CapturingSseEmitter(),
                callback,
                CancellationToken.NONE
        );

        assertTrue(routed);
        verify(callback).onContent("确认失败：无权限确认该提案");
        verify(callback).onComplete();
        verifyNoInteractions(mcpService);
    }

    @Test
    void shouldRollbackWhenConfirmedExecutionFailsWithMissingParam() {
        PendingProposal proposal = PendingProposal.builder()
                .proposalId("p-4")
                .conversationId("c1")
                .userId("u1")
                .toolId("obsidian_create")
                .userQuestion("create")
                .parameters(new HashMap<>())
                .status(PendingProposalStore.STATUS_PENDING)
                .createdAt(System.currentTimeMillis())
                .expiresAt(System.currentTimeMillis() + 10000)
                .build();
        when(pendingProposalStore.confirm(eq("p-4"), eq("c1"), eq("u1")))
                .thenReturn(new PendingProposalStore.ProposalDecisionResult(true, proposal, null));
        when(pendingProposalStore.rollbackToPending(eq("p-4"), eq("c1"), eq("u1"), any()))
                .thenReturn(new PendingProposalStore.ProposalDecisionResult(true, proposal, null));
        when(mcpService.execute(any())).thenReturn(MCPResponse.error("obsidian_create", "MISSING_PARAM", "必须提供 name 参数"));

        boolean routed = router.tryRoute(
                "/confirm p-4",
                "c1",
                "u1",
                "t1",
                new CapturingSseEmitter(),
                callback,
                CancellationToken.NONE
        );

        assertTrue(routed);
        verify(pendingProposalStore).rollbackToPending(eq("p-4"), eq("c1"), eq("u1"), any());
        verify(callback).onContent(contains("补全参数后重试"));
        verify(callback).onComplete();
    }

    private static final class CapturingSseEmitter extends SseEmitter {

        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> rawPayloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            eventNames.add(extractEventName(parts));
            for (ResponseBodyEmitter.DataWithMediaType each : parts) {
                rawPayloads.add(each.getData());
            }
        }

        private String extractEventName(Set<ResponseBodyEmitter.DataWithMediaType> parts) {
            for (ResponseBodyEmitter.DataWithMediaType each : parts) {
                Object data = each.getData();
                if (data instanceof String line && line.startsWith("event:")) {
                    return line.substring("event:".length()).trim();
                }
            }
            return "";
        }
    }
}
