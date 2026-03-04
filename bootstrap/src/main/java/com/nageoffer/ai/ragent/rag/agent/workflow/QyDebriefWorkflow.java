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

package com.nageoffer.ai.ragent.rag.agent.workflow;

import com.nageoffer.ai.ragent.rag.agent.AgentCommand;
import com.nageoffer.ai.ragent.rag.agent.AgentWorkflow;
import com.nageoffer.ai.ragent.rag.agent.AgentWorkflowContext;
import com.nageoffer.ai.ragent.rag.agent.AgentWorkflowResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /qy-debrief 最小可用工作流
 */
@Component
public class QyDebriefWorkflow implements AgentWorkflow {

    private static final String WORKFLOW_ID = "/qy-debrief";

    @Override
    public String id() {
        return WORKFLOW_ID;
    }

    @Override
    public boolean supports(AgentCommand command) {
        return command != null && WORKFLOW_ID.equalsIgnoreCase(command.workflowId());
    }

    @Override
    public AgentWorkflowResult execute(AgentWorkflowContext context) {
        String args = context == null || context.command() == null ? "" : context.command().args();
        String reply = args == null || args.isBlank()
                ? "已执行 /qy-debrief 工作流（最小可用模式，暂未启用写回）。"
                : "已执行 /qy-debrief 工作流（最小可用模式），参数：" + args;
        return AgentWorkflowResult.builder()
                .reply(reply)
                .changedFiles(List.of())
                .opsCount(0)
                .warnings(List.of())
                .build();
    }
}
