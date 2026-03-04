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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPService;
import com.nageoffer.ai.ragent.rag.core.cancel.CancellationToken;
import com.nageoffer.ai.ragent.rag.dto.WorkflowEventPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 命令路由器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCommandRouter {

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^/(qy-review|qy-job|qy-debrief|confirm|reject)(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);

    private final AgentWorkflowRegistry workflowRegistry;
    private final PendingProposalStore pendingProposalStore;
    private final MCPService mcpService;

    @RagTraceNode(name = "agent-command-route", type = "AGENT_ROUTE")
    public boolean tryRoute(String question,
                            String conversationId,
                            String userId,
                            String taskId,
                            SseEmitter emitter,
                            StreamCallback callback,
                            CancellationToken token) {
        AgentCommand command = parseCommand(question);
        if (command == null) {
            return false;
        }

        if (isProposalCommand(command)) {
            handleProposalCommand(command, conversationId, userId, emitter, callback);
            return true;
        }

        AgentWorkflow workflow = workflowRegistry.find(command).orElse(null);
        if (workflow == null) {
            return false;
        }

        try {
            token.throwIfCancelled();
            AgentWorkflowContext context = AgentWorkflowContext.builder()
                    .command(command)
                    .conversationId(conversationId)
                    .taskId(taskId)
                    .userId(userId)
                    .token(token)
                    .build();

            AgentWorkflowResult result = workflow.execute(context);
            sendWorkflowEvent(emitter, result, workflow.id());
            String reply = result.reply() == null ? "已处理。" : result.reply();
            callback.onContent(reply);
            callback.onComplete();
            return true;
        } catch (Exception ex) {
            log.error("Agent 工作流执行失败，降级走默认 RAG。workflow={}, question={}",
                    workflow.id(), question, ex);
            return false;
        }
    }

    private AgentCommand parseCommand(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher matcher = COMMAND_PATTERN.matcher(question.trim());
        if (!matcher.matches()) {
            return null;
        }
        String workflowId = "/" + matcher.group(1).toLowerCase();
        String args = matcher.group(2) == null ? "" : matcher.group(2).trim();
        return AgentCommand.builder()
                .workflowId(workflowId)
                .rawInput(question)
                .args(args)
                .build();
    }

    private boolean isProposalCommand(AgentCommand command) {
        if (command == null || command.workflowId() == null) {
            return false;
        }
        return "/confirm".equalsIgnoreCase(command.workflowId())
                || "/reject".equalsIgnoreCase(command.workflowId());
    }

    @RagTraceNode(name = "agent-confirm", type = "AGENT_CONFIRM")
    protected void handleProposalCommand(AgentCommand command,
                                         String conversationId,
                                         String userId,
                                         SseEmitter emitter,
                                         StreamCallback callback) {
        String proposalId = command.args() == null ? "" : command.args().trim();
        if (proposalId.isBlank()) {
            callback.onContent("命令参数缺失。请使用 `/confirm <proposalId>` 或 `/reject <proposalId>`。");
            callback.onComplete();
            return;
        }

        if ("/reject".equalsIgnoreCase(command.workflowId())) {
            PendingProposalStore.ProposalDecisionResult rejected = pendingProposalStore.reject(proposalId, conversationId, userId);
            if (!rejected.success()) {
                callback.onContent("拒绝失败：" + rejected.message());
                callback.onComplete();
                return;
            }
            callback.onContent("已拒绝提案：" + proposalId);
            callback.onComplete();
            return;
        }

        PendingProposalStore.ProposalDecisionResult confirmed = pendingProposalStore.confirm(proposalId, conversationId, userId);
        if (!confirmed.success()) {
            callback.onContent("确认失败：" + confirmed.message());
            callback.onComplete();
            return;
        }

        PendingProposal proposal = confirmed.proposal();
        MCPResponse response = mcpService.execute(proposal.toMcpRequest());
        if (!response.isSuccess()) {
            callback.onContent("已确认提案，但执行失败：" + response.getErrorMessage());
            callback.onComplete();
            return;
        }

        sendWorkflowEvent(emitter, AgentWorkflowResult.builder()
                .reply(response.getTextResult())
                .changedFiles(proposal.getTargetPath() == null ? java.util.List.of() : java.util.List.of(proposal.getTargetPath()))
                .opsCount(1)
                .warnings(java.util.List.of())
                .build(), "/confirm");

        callback.onContent(StrUtil.blankToDefault(response.getTextResult(), "提案已确认并执行完成。"));
        callback.onComplete();
    }

    @RagTraceNode(name = "agent-workflow-summary", type = "AGENT_SUMMARY")
    protected void sendWorkflowEvent(SseEmitter emitter, AgentWorkflowResult result, String workflowId) {
        if (emitter == null || result == null) {
            return;
        }
        try {
            WorkflowEventPayload payload = new WorkflowEventPayload(
                    workflowId,
                    result.changedFiles(),
                    result.opsCount(),
                    result.warnings()
            );
            emitter.send(SseEmitter.event()
                    .name(SSEEventType.WORKFLOW.value())
                    .data(payload));
        } catch (Exception e) {
            log.warn("发送 workflow SSE 事件失败", e);
        }
    }
}
