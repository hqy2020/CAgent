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

import com.openingcloud.ai.ragent.rag.config.VideoTranscriptProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianCliExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianVideoTranscriptTool;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.VideoTranscriptApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObsidianVideoTranscriptToolTests {

    @Mock
    private VideoTranscriptApiClient transcriptApiClient;

    @Mock
    private ObsidianCliExecutor cliExecutor;

    private ObsidianVideoTranscriptTool tool;

    @BeforeEach
    void setUp() {
        VideoTranscriptProperties properties = new VideoTranscriptProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:8000");
        properties.setDefaultNotePath("Inbox/Video");
        properties.setDefaultUseSpeakerRecognition(false);
        properties.setAppendIfExists(true);
        properties.setPollTimeoutSeconds(1200);
        properties.setPollIntervalMillis(1500);
        tool = new ObsidianVideoTranscriptTool(transcriptApiClient, cliExecutor, properties);
    }

    @Test
    void shouldCreateNoteWhenTranscriptionSuccess() {
        when(transcriptApiClient.submitTask(anyString(), any(), anyBoolean()))
                .thenReturn(new VideoTranscriptApiClient.SubmitResult("task-1", "view-1", "任务已提交"));
        when(transcriptApiClient.pollTaskResult(eq("task-1"), any(Duration.class), any(Duration.class)))
                .thenReturn(new VideoTranscriptApiClient.TaskResult("task-1", "转录成功", "B站讲解", "UP主", "这是转录正文"));
        when(cliExecutor.execute(eq("create"), any()))
                .thenReturn(new ObsidianCliExecutor.CliResult(0, "ok", ""));

        MCPRequest request = request(Map.of("url", "https://www.bilibili.com/video/BV1xx4y1"));
        MCPResponse response = tool.handle(request);

        assertTrue(response.isSuccess());
        assertEquals("obsidian_video_transcript", response.getToolId());
        assertEquals("created", response.getData().get("mode"));
        assertEquals("Inbox/Video/B站讲解.md", response.getData().get("notePath"));

        ArgumentCaptor<List<String>> createArgs = ArgumentCaptor.forClass(List.class);
        verify(cliExecutor).execute(eq("create"), createArgs.capture());
        List<String> args = createArgs.getValue();
        assertTrue(args.contains("name=B站讲解"));
        assertTrue(args.contains("path=Inbox/Video"));
    }

    @Test
    void shouldAppendWhenNoteExists() {
        when(transcriptApiClient.submitTask(anyString(), any(), anyBoolean()))
                .thenReturn(new VideoTranscriptApiClient.SubmitResult("task-2", "view-2", "任务已提交"));
        when(transcriptApiClient.pollTaskResult(eq("task-2"), any(Duration.class), any(Duration.class)))
                .thenReturn(new VideoTranscriptApiClient.TaskResult("task-2", "转录成功", "播客标题", "主播", "播客正文"));
        when(cliExecutor.execute(eq("create"), any()))
                .thenReturn(new ObsidianCliExecutor.CliResult(1, "", "笔记已存在: Inbox/Video/播客.md"));
        when(cliExecutor.execute(eq("append"), any()))
                .thenReturn(new ObsidianCliExecutor.CliResult(0, "ok", ""));

        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://www.xiaoyuzhoufm.com/episode/abc");
        params.put("path", "Inbox/Video");
        params.put("noteName", "播客");
        MCPRequest request = request(params);

        MCPResponse response = tool.handle(request);

        assertTrue(response.isSuccess());
        assertEquals("appended", response.getData().get("mode"));
        assertEquals("Inbox/Video/播客.md", response.getData().get("notePath"));

        ArgumentCaptor<List<String>> appendArgs = ArgumentCaptor.forClass(List.class);
        verify(cliExecutor).execute(eq("append"), appendArgs.capture());
        List<String> args = appendArgs.getValue();
        assertTrue(args.stream().anyMatch(each -> each.equals("path=Inbox/Video/播客.md")));
    }

    @Test
    void shouldReturnErrorWhenUrlMissing() {
        MCPRequest request = request(Map.of());

        MCPResponse response = tool.handle(request);

        assertFalse(response.isSuccess());
        assertEquals("MISSING_PARAM", response.getErrorCode());
        verifyNoInteractions(transcriptApiClient, cliExecutor);
    }

    @Test
    void shouldReturnErrorWhenApiClientFails() {
        when(transcriptApiClient.submitTask(anyString(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("video-transcript.auth-token 未配置"));

        MCPRequest request = request(Map.of("url", "https://www.bilibili.com/video/BV1xx4y1"));
        MCPResponse response = tool.handle(request);

        assertFalse(response.isSuccess());
        assertEquals("TRANSCRIBE_FAILED", response.getErrorCode());
        assertTrue(response.getErrorMessage().contains("auth-token"));
    }

    private MCPRequest request(Map<String, Object> params) {
        return MCPRequest.builder()
                .toolId("obsidian_video_transcript")
                .userQuestion("转录视频")
                .parameters(new HashMap<>(params))
                .build();
    }
}
