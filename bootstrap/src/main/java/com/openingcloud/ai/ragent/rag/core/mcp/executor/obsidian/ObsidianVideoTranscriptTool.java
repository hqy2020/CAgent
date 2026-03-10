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

package com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian;

import com.openingcloud.ai.ragent.rag.config.VideoTranscriptProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPExecute;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPParam;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPToolDeclare;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Obsidian MCP 工具 — 视频转录入库
 */
@Slf4j
@Component
@RequiredArgsConstructor
@MCPToolDeclare(
        toolId = "obsidian_video_transcript",
        name = "视频转录写入 Obsidian",
        description = "将视频或播客链接转录为文字，并自动写入 Obsidian 笔记。",
        useWhen = "当用户提供视频、播客或长内容链接，并明确要求转录后落盘到 Obsidian 时使用。",
        avoidWhen = "不要用于普通网页搜索、仅创建空白笔记、只更新一小段文本或查询已有笔记。",
        examples = {
                "把这个 B 站链接转录成文字并写进 Obsidian",
                "转录这个小宇宙链接，存到视频转录目录",
                "把这个 YouTube 视频转录后追加到现有笔记"
        },
        requireUserId = true,
        operationType = MCPTool.OperationType.WRITE,
        confirmationRequired = true,
        timeoutSeconds = 30,
        sensitivity = MCPTool.Sensitivity.HIGH,
        sensitiveParams = {"sourceUrl", "url"},
        fallbackMessage = "视频转录写入暂时不可用，本次不会执行写入。",
        parameters = {
                @MCPParam(name = "url", description = "视频或播客链接（必填）", type = "string",
                        required = true, example = "https://www.bilibili.com/video/BV1xx411c7mD", pattern = "^https?://.+"),
                @MCPParam(name = "sourceUrl", description = "原始来源链接（可选）", type = "string",
                        required = false, example = "https://www.youtube.com/watch?v=demo", pattern = "^https?://.+"),
                @MCPParam(name = "path", description = "写入的 Obsidian 目录（相对 vault 路径）", type = "string",
                        required = false, example = "2-Inputs/Transcripts"),
                @MCPParam(name = "noteName", description = "笔记名（不含 .md，可选）", type = "string",
                        required = false, example = "AI 工具调用访谈转录"),
                @MCPParam(name = "useSpeakerRecognition", description = "是否开启说话人识别", type = "string", required = false,
                        defaultValue = "false", example = "false", enumValues = {"true", "false"}),
                @MCPParam(name = "appendIfExists", description = "若笔记已存在是否追加写入", type = "string", required = false,
                        defaultValue = "true", example = "true", enumValues = {"true", "false"}),
                @MCPParam(name = "pollTimeoutSeconds", description = "轮询超时秒数", type = "number",
                        required = false, example = "180")
        }
)
public class ObsidianVideoTranscriptTool {

    private static final String TOOL_ID = "obsidian_video_transcript";
    private static final DateTimeFormatter RECORD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VideoTranscriptApiClient transcriptApiClient;
    private final ObsidianCliExecutor cliExecutor;
    private final VideoTranscriptProperties properties;

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String url = request.getStringParameter("url");
        if (url == null || url.isBlank()) {
            return MCPResponse.error(TOOL_ID, "MISSING_PARAM", "必须提供 url 参数");
        }

        String sourceUrl = request.getStringParameter("sourceUrl");
        String path = firstNonBlank(request.getStringParameter("path"), properties.getDefaultNotePath());
        String noteName = request.getStringParameter("noteName");

        boolean useSpeakerRecognition = resolveBoolean(
                request.getStringParameter("useSpeakerRecognition"),
                properties.isDefaultUseSpeakerRecognition()
        );
        boolean appendIfExists = resolveBoolean(
                request.getStringParameter("appendIfExists"),
                properties.isAppendIfExists()
        );
        int timeoutSeconds = resolvePositiveInt(
                request.getStringParameter("pollTimeoutSeconds"),
                properties.getPollTimeoutSeconds()
        );

        try {
            VideoTranscriptApiClient.SubmitResult submitResult =
                    transcriptApiClient.submitTask(url, sourceUrl, useSpeakerRecognition);

            VideoTranscriptApiClient.TaskResult taskResult = transcriptApiClient.pollTaskResult(
                    submitResult.taskId(),
                    Duration.ofSeconds(timeoutSeconds),
                    Duration.ofMillis(Math.max(properties.getPollIntervalMillis(), 500))
            );

            String transcript = taskResult.transcript();
            if (transcript == null || transcript.isBlank()) {
                return MCPResponse.error(TOOL_ID, "EMPTY_TRANSCRIPT", "转录成功但返回文本为空");
            }

            String finalNoteName = resolveNoteName(noteName, taskResult.videoTitle(), url);
            String noteContent = buildNoteContent(url, sourceUrl, useSpeakerRecognition, submitResult, taskResult, transcript);
            String noteRelativePath = buildRelativePath(path, finalNoteName);

            ObsidianCliExecutor.CliResult createResult = createNote(path, finalNoteName, noteContent);
            String mode;
            if (createResult.isSuccess()) {
                mode = "created";
            } else if (appendIfExists && isAlreadyExists(createResult.stderr())) {
                ObsidianCliExecutor.CliResult appendResult = appendToNote(noteRelativePath, noteContent);
                if (!appendResult.isSuccess()) {
                    return MCPResponse.error(TOOL_ID, "OBSIDIAN_APPEND_FAILED", appendResult.stderr());
                }
                mode = "appended";
            } else {
                return MCPResponse.error(TOOL_ID, "OBSIDIAN_CREATE_FAILED", createResult.stderr());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", submitResult.taskId());
            data.put("viewToken", submitResult.viewToken());
            data.put("videoTitle", taskResult.videoTitle());
            data.put("author", taskResult.author());
            data.put("notePath", noteRelativePath);
            data.put("mode", mode);
            data.put("sourceUrl", firstNonBlank(sourceUrl, url));
            data.put("speakerRecognition", useSpeakerRecognition);

            String resultText = "视频转录已写入 Obsidian。\n"
                    + "笔记路径: " + noteRelativePath + "\n"
                    + "写入模式: " + ("created".equals(mode) ? "新建" : "追加") + "\n"
                    + "标题: " + safeText(taskResult.videoTitle(), "未命名视频");

            return MCPResponse.success(TOOL_ID, resultText, data);
        } catch (Exception e) {
            log.error("视频转录写入 Obsidian 失败, url={}", url, e);
            return MCPResponse.error(TOOL_ID, "TRANSCRIBE_FAILED", e.getMessage());
        }
    }

    private ObsidianCliExecutor.CliResult createNote(String path, String noteName, String content) {
        List<String> args = new ArrayList<>();
        args.add("name=" + noteName);
        if (path != null && !path.isBlank()) {
            args.add("path=" + path);
        }
        args.add("content=" + content);
        return cliExecutor.execute("create", args);
    }

    private ObsidianCliExecutor.CliResult appendToNote(String noteRelativePath, String content) {
        List<String> args = new ArrayList<>();
        args.add("path=" + noteRelativePath);
        args.add("content=" + "\n\n---\n\n" + content);
        return cliExecutor.execute("append", args);
    }

    private String buildNoteContent(String url,
                                    String sourceUrl,
                                    boolean useSpeakerRecognition,
                                    VideoTranscriptApiClient.SubmitResult submitResult,
                                    VideoTranscriptApiClient.TaskResult taskResult,
                                    String transcript) {
        String title = safeText(taskResult.videoTitle(), "未命名视频");
        String author = safeText(taskResult.author(), "未知");
        String displayUrl = firstNonBlank(sourceUrl, url);
        String recordTime = LocalDateTime.now().format(RECORD_TIME_FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("## 转录记录 ").append(recordTime).append("\n\n");
        sb.append("### ").append(title).append("\n\n");
        sb.append("- 来源链接: ").append(displayUrl).append("\n");
        sb.append("- 作者: ").append(author).append("\n");
        sb.append("- 任务ID: ").append(submitResult.taskId()).append("\n");
        if (submitResult.viewToken() != null && !submitResult.viewToken().isBlank()) {
            sb.append("- 查看链接: ").append(buildViewUrl(submitResult.viewToken())).append("\n");
        }
        sb.append("- 说话人识别: ").append(useSpeakerRecognition ? "开启" : "关闭").append("\n\n");
        sb.append("## 转录正文\n\n");
        sb.append(transcript.strip()).append("\n");
        return sb.toString();
    }

    private String buildViewUrl(String viewToken) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "/view/" + viewToken;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + "/view/" + viewToken;
    }

    private String resolveNoteName(String providedName, String videoTitle, String url) {
        String baseName = firstNonBlank(providedName, videoTitle);
        if (baseName == null || baseName.isBlank()) {
            baseName = "视频转录-" + extractHost(url) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        String normalized = sanitizeFileName(baseName);
        if (normalized.toLowerCase(Locale.ROOT).endsWith(".md")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.isBlank() ? "视频转录" : normalized;
    }

    private String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
        return sanitized.isBlank() ? "" : sanitized;
    }

    private String buildRelativePath(String path, String noteName) {
        String fileName = noteName.endsWith(".md") ? noteName : noteName + ".md";
        if (path == null || path.isBlank()) {
            return fileName;
        }
        String normalizedPath = path.replace("\\", "/");
        while (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        if (normalizedPath.isBlank()) {
            return fileName;
        }
        return normalizedPath + "/" + fileName;
    }

    private String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "unknown";
            }
            return host.replace("www.", "");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean isAlreadyExists(String error) {
        return error != null && error.contains("笔记已存在");
    }

    private boolean resolveBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "y", "on", "是", "开启" -> true;
            case "false", "0", "no", "n", "off", "否", "关闭" -> false;
            default -> defaultValue;
        };
    }

    private int resolvePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue > 0 ? defaultValue : 1200;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String safeText(String text, String defaultValue) {
        return (text == null || text.isBlank()) ? defaultValue : text;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
