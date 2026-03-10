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

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.rag.config.ExternalMcpProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.external.ExternalMcpCallRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.external.ExternalMcpCallResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.external.ExternalMcpClient;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 将本地 Obsidian 命令映射到 mcp-obsidian 工具调用。
 */
@Component
@RequiredArgsConstructor
public class ObsidianExternalMcpGateway {

    private static final String DAILY_NOTE_PATH_PATTERN = "2-Resource（参考资源）/80_生活记录/DailyNote/日记";

    private final ExternalMcpClient externalMcpClient;
    private final ExternalMcpProperties externalMcpProperties;

    public ExternalExecuteResult tryExecute(String command, Map<String, String> params) {
        ExternalMcpProperties.ExternalServerConfig config = externalMcpProperties.getObsidian();
        if (!config.isEnabled()) {
            return ExternalExecuteResult.skipped();
        }
        String mode = normalizeMode(config.getMode());
        if ("local-only".equals(mode)) {
            return ExternalExecuteResult.skipped();
        }

        Optional<ExternalCallPlan> planOpt = mapToCallPlan(command, params);
        if (planOpt.isEmpty()) {
            return ExternalExecuteResult.unsupported("external obsidian mapping missing for command: " + command);
        }

        ExternalCallPlan plan = planOpt.get();
        ExternalMcpCallResponse response = externalMcpClient.callTool(ExternalMcpCallRequest.builder()
                .serverCommand(composeServerCommand(config.getCommand(), config.getArgs()))
                .toolName(plan.toolName())
                .arguments(plan.arguments())
                .env(config.getEnv())
                .cwd(null)
                .timeoutSeconds(Math.max(10, config.getTimeoutSeconds()))
                .build());

        if (response.success()) {
            String text = StrUtil.blankToDefault(response.textResult(), "external obsidian tool executed");
            return ExternalExecuteResult.success(text);
        }
        String error = StrUtil.blankToDefault(response.errorMessage(), "external obsidian call failed");
        return ExternalExecuteResult.failed(response.errorCode(), error);
    }

    public boolean externalOnly() {
        return "external-only".equals(normalizeMode(externalMcpProperties.getObsidian().getMode()));
    }

    private Optional<ExternalCallPlan> mapToCallPlan(String command, Map<String, String> params) {
        if (StrUtil.isBlank(command)) {
            return Optional.empty();
        }
        return switch (command) {
            case "read" -> mapRead(params);
            case "search", "search:context" -> mapSearch(params);
            case "files" -> mapFiles(params);
            case "folders" -> Optional.empty();
            case "create" -> mapCreate(params);
            case "append" -> mapAppend(params);
            case "daily:append" -> mapDailyAppend(params);
            case "delete" -> mapDelete(params);
            case "replace" -> Optional.empty();
            case "prepend" -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<ExternalCallPlan> mapRead(Map<String, String> params) {
        String filepath = resolveFilePath(params);
        if (StrUtil.isBlank(filepath)) {
            return Optional.empty();
        }
        return Optional.of(new ExternalCallPlan(
                "obsidian_get_file_contents",
                Map.of("filepath", filepath)
        ));
    }

    private Optional<ExternalCallPlan> mapSearch(Map<String, String> params) {
        String query = params.get("query");
        if (StrUtil.isBlank(query)) {
            return Optional.empty();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query);

        String limit = params.get("limit");
        Integer contextLength = parseInteger(limit);
        arguments.put("context_length", contextLength == null ? 120 : Math.max(20, contextLength * 12));
        return Optional.of(new ExternalCallPlan("obsidian_simple_search", arguments));
    }

    private Optional<ExternalCallPlan> mapFiles(Map<String, String> params) {
        String folder = params.get("folder");
        if (StrUtil.isBlank(folder)) {
            return Optional.of(new ExternalCallPlan("obsidian_list_files_in_vault", Map.of()));
        }
        return Optional.of(new ExternalCallPlan("obsidian_list_files_in_dir", Map.of("dirpath", folder)));
    }

    private Optional<ExternalCallPlan> mapCreate(Map<String, String> params) {
        String name = params.get("name");
        if (StrUtil.isBlank(name)) {
            return Optional.empty();
        }
        String path = params.get("path");
        String content = StrUtil.blankToDefault(params.get("content"), "");
        String normalizedName = ensureMarkdownSuffix(name);

        String filepath = StrUtil.isBlank(path)
                ? normalizedName
                : normalizePath(path) + "/" + normalizedName;

        return Optional.of(new ExternalCallPlan(
                "obsidian_put_content",
                Map.of(
                        "filepath", filepath,
                        "content", content
                )
        ));
    }

    private Optional<ExternalCallPlan> mapAppend(Map<String, String> params) {
        String content = params.get("content");
        String filepath = resolveFilePath(params);
        if (StrUtil.isBlank(content) || StrUtil.isBlank(filepath)) {
            return Optional.empty();
        }
        return Optional.of(new ExternalCallPlan(
                "obsidian_append_content",
                Map.of(
                        "filepath", filepath,
                        "content", content
                )
        ));
    }

    private Optional<ExternalCallPlan> mapDailyAppend(Map<String, String> params) {
        String content = params.get("content");
        if (StrUtil.isBlank(content)) {
            return Optional.empty();
        }
        String date = StrUtil.blankToDefault(params.get("date"), LocalDate.now().toString());
        String filepath = DAILY_NOTE_PATH_PATTERN + "/" + ensureMarkdownSuffix(date);
        return Optional.of(new ExternalCallPlan(
                "obsidian_append_content",
                Map.of(
                        "filepath", filepath,
                        "content", content
                )
        ));
    }

    private Optional<ExternalCallPlan> mapDelete(Map<String, String> params) {
        String filepath = resolveFilePath(params);
        if (StrUtil.isBlank(filepath)) {
            return Optional.empty();
        }
        return Optional.of(new ExternalCallPlan(
                "obsidian_delete_file",
                Map.of(
                        "filepath", filepath,
                        "confirm", true
                )
        ));
    }

    private String resolveFilePath(Map<String, String> params) {
        String path = params.get("path");
        if (StrUtil.isNotBlank(path)) {
            return ensureMarkdownSuffix(normalizePath(path));
        }
        String file = params.get("file");
        if (StrUtil.isNotBlank(file)) {
            return ensureMarkdownSuffix(file);
        }
        return null;
    }

    private String normalizePath(String path) {
        String normalized = StrUtil.blankToDefault(path, "").trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String ensureMarkdownSuffix(String filename) {
        if (StrUtil.isBlank(filename)) {
            return filename;
        }
        String trimmed = filename.trim();
        if (trimmed.endsWith(".md")) {
            return trimmed;
        }
        return trimmed + ".md";
    }

    private Integer parseInteger(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private String normalizeMode(String mode) {
        if (StrUtil.isBlank(mode)) {
            return "external-first";
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    private String composeServerCommand(String command, java.util.List<String> args) {
        StringBuilder builder = new StringBuilder(StrUtil.blankToDefault(command, "").trim());
        if (args != null) {
            for (String arg : args) {
                if (StrUtil.isBlank(arg)) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(arg.trim());
            }
        }
        return builder.toString();
    }

    private record ExternalCallPlan(String toolName, Map<String, Object> arguments) {
    }

    @Builder
    public record ExternalExecuteResult(boolean attempted,
                                        boolean success,
                                        boolean unsupported,
                                        String errorCode,
                                        String errorMessage,
                                        String textResult) {

        static ExternalExecuteResult skipped() {
            return ExternalExecuteResult.builder().attempted(false).success(false).build();
        }

        static ExternalExecuteResult unsupported(String message) {
            return ExternalExecuteResult.builder()
                    .attempted(true)
                    .unsupported(true)
                    .errorCode("UNSUPPORTED_COMMAND")
                    .errorMessage(message)
                    .build();
        }

        static ExternalExecuteResult failed(String code, String message) {
            return ExternalExecuteResult.builder()
                    .attempted(true)
                    .errorCode(StrUtil.blankToDefault(code, "CALL_ERROR"))
                    .errorMessage(message)
                    .build();
        }

        static ExternalExecuteResult success(String textResult) {
            return ExternalExecuteResult.builder()
                    .attempted(true)
                    .success(true)
                    .textResult(textResult)
                    .build();
        }
    }
}
