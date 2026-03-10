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

package com.openingcloud.ai.ragent.rag.mcp;

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianCliExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianUpdateNoteTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObsidianUpdateNoteToolTests {

    @Mock
    private ObsidianCliExecutor cliExecutor;

    private ObsidianUpdateNoteTool updateTool;

    @BeforeEach
    void setUp() {
        updateTool = new ObsidianUpdateNoteTool(cliExecutor);
    }

    @Test
    void shouldPreferTodayDailyAndUseTodoTailContent() {
        when(cliExecutor.execute(eq("daily:append"), anyList()))
                .thenReturn(new ObsidianCliExecutor.CliResult(0, "ok", ""));

        MCPRequest request = buildDailyRequest(
                "帮我往今日日记加一条待办，3.7答辩",
                "答辩待办事项",
                "2023-03-07"
        );

        MCPResponse response = updateTool.handle(request);

        assertTrue(response.isSuccess());
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cliExecutor).execute(eq("daily:append"), argsCaptor.capture());
        List<String> args = argsCaptor.getValue();
        assertTrue(args.contains("date=" + LocalDate.now()));
        assertEquals("content=- [ ] 3.7答辩", findArg(args, "content="));
    }

    @Test
    void shouldReturnDateConflictWhenTodayAndExplicitDailyDateDiffer() {
        LocalDate today = LocalDate.now();
        LocalDate target = today.plusDays(1);
        MCPRequest request = buildDailyRequest(
                String.format("帮我往今日日记加一条待办，写到%d月%d日日记里", target.getMonthValue(), target.getDayOfMonth()),
                "答辩",
                target.toString()
        );

        MCPResponse response = updateTool.handle(request);

        assertFalse(response.isSuccess());
        assertEquals("DATE_CONFLICT", response.getErrorCode());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains(today.toString()));
        assertTrue(response.getErrorMessage().contains(target.toString()));
        verifyNoInteractions(cliExecutor);
    }

    @Test
    void shouldResolveMonthDayDailyToCurrentYear() {
        when(cliExecutor.execute(eq("daily:append"), anyList()))
                .thenReturn(new ObsidianCliExecutor.CliResult(0, "ok", ""));

        MCPRequest request = buildDailyRequest(
                "帮我往3.7日记加一条待办，答辩",
                "答辩待办事项",
                "2023-03-07"
        );

        MCPResponse response = updateTool.handle(request);

        assertTrue(response.isSuccess());
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cliExecutor).execute(eq("daily:append"), argsCaptor.capture());
        List<String> args = argsCaptor.getValue();
        LocalDate expected = LocalDate.of(LocalDate.now().getYear(), 3, 7);
        assertTrue(args.contains("date=" + expected));
        assertEquals("content=- [ ] 答辩", findArg(args, "content="));
    }

    private MCPRequest buildDailyRequest(String question, String content, String date) {
        Map<String, Object> params = new HashMap<>();
        params.put("daily", "true");
        params.put("content", content);
        if (date != null) {
            params.put("date", date);
        }
        return MCPRequest.builder()
                .toolId("obsidian_update")
                .userQuestion(question)
                .parameters(params)
                .build();
    }

    private String findArg(List<String> args, String prefix) {
        return args.stream()
                .filter(each -> each.startsWith(prefix))
                .findFirst()
                .orElse("");
    }
}
