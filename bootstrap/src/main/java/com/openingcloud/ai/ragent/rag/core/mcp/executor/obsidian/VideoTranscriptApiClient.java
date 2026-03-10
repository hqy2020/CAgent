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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.rag.config.VideoTranscriptProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VideoTranscriptAPI 客户端
 */
@Component
@RequiredArgsConstructor
public class VideoTranscriptApiClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final VideoTranscriptProperties properties;

    public SubmitResult submitTask(String url, String sourceUrl, boolean useSpeakerRecognition) {
        ensureConfigured();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        payload.put("use_speaker_recognition", useSpeakerRecognition);
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            payload.put("source_url", sourceUrl);
        }

        try {
            byte[] bodyBytes = objectMapper.writeValueAsBytes(payload);
            Request request = new Request.Builder()
                    .url(buildUrl("/api/transcribe"))
                    .post(RequestBody.create(bodyBytes, JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer " + properties.getAuthToken().trim())
                    .addHeader("Content-Type", "application/json")
                    .build();

            JsonNode root = executeJson(request, Duration.ofSeconds(normalizePositive(properties.getSubmitTimeoutSeconds(), 30)));
            int code = root.path("code").asInt(-1);
            if (code != 200 && code != 202) {
                String message = root.path("message").asText("提交转录任务失败");
                throw new IllegalStateException("提交转录任务失败: " + message + " (code=" + code + ")");
            }

            JsonNode data = root.path("data");
            String taskId = textValue(data, "task_id");
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalStateException("提交转录任务失败: 未返回 task_id");
            }
            String viewToken = textValue(data, "view_token");
            String message = root.path("message").asText("任务已提交");

            return new SubmitResult(taskId, viewToken, message);
        } catch (IOException e) {
            throw new IllegalStateException("提交转录任务失败: " + e.getMessage(), e);
        }
    }

    public TaskResult pollTaskResult(String taskId, Duration timeout, Duration pollInterval) {
        ensureConfigured();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Duration interval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofSeconds(3)
                : pollInterval;

        while (System.nanoTime() <= deadlineNanos) {
            Request request = new Request.Builder()
                    .url(buildUrl("/api/task/" + taskId))
                    .get()
                    .addHeader("Authorization", "Bearer " + properties.getAuthToken().trim())
                    .build();

            JsonNode root = executeJson(request, Duration.ofSeconds(30));
            int code = root.path("code").asInt(-1);
            String message = root.path("message").asText("获取任务状态成功");
            JsonNode data = root.path("data");

            if (code == 200) {
                return new TaskResult(
                        taskId,
                        message,
                        textValue(data, "video_title"),
                        textValue(data, "author"),
                        textValue(data, "transcript")
                );
            }

            if (code >= 500) {
                throw new IllegalStateException("转录任务失败: " + message + " (taskId=" + taskId + ")");
            }

            if (code != 202) {
                throw new IllegalStateException("转录任务状态异常: code=" + code + ", message=" + message);
            }

            sleep(interval);
        }

        throw new IllegalStateException("转录任务超时未完成: taskId=" + taskId + ", timeout=" + timeout.getSeconds() + "s");
    }

    private JsonNode executeJson(Request request, Duration timeout) {
        OkHttpClient scopedClient = okHttpClient.newBuilder()
                .callTimeout(timeout)
                .build();

        try (Response response = scopedClient.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("请求失败: HTTP " + response.code() + ", body=" + raw);
            }
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("请求失败: 返回内容为空");
            }
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException("请求失败: " + e.getMessage(), e);
        }
    }

    private void ensureConfigured() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("video-transcript.enabled=false，未启用视频转录集成");
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("video-transcript.base-url 未配置");
        }
        if (properties.getAuthToken() == null || properties.getAuthToken().isBlank()) {
            throw new IllegalStateException("video-transcript.auth-token 未配置");
        }
    }

    private String buildUrl(String path) {
        String base = properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private String textValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(duration.toMillis(), 200L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询任务被中断", e);
        }
    }

    private int normalizePositive(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    public record SubmitResult(String taskId, String viewToken, String message) {
    }

    public record TaskResult(String taskId, String message, String videoTitle, String author, String transcript) {
    }
}
