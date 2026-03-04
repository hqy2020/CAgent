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

import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
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

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^/(qy-review|qy-job|qy-debrief)(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);

    private final AgentWorkflowRegistry workflowRegistry;

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
