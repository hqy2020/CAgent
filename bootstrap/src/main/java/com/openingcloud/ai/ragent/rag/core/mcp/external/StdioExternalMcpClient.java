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

package com.openingcloud.ai.ragent.rag.core.mcp.external;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.rag.config.ExternalMcpProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPToolHealthStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 通过 Node bridge 脚本发起 stdio MCP 调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StdioExternalMcpClient implements ExternalMcpClient {

    private final ExternalMcpProperties externalMcpProperties;
    private final ObjectMapper objectMapper;
    private final MCPToolHealthStore healthStore;

    private final AtomicBoolean bridgeReady = new AtomicBoolean(false);
    private final ReentrantLock installLock = new ReentrantLock();

    @Override
    public ExternalMcpCallResponse callTool(ExternalMcpCallRequest request) {
        if (request == null || StrUtil.isBlank(request.serverCommand()) || StrUtil.isBlank(request.toolName())) {
            return ExternalMcpCallResponse.error("INVALID_REQUEST", "external mcp request invalid", "", "");
        }

        ResolvedServer resolvedServer = resolveAllowedServer(request.serverCommand());
        if (resolvedServer == null) {
            return ExternalMcpCallResponse.error(
                    "SECURITY_VIOLATION",
                    "external mcp server command is not allowlisted",
                    "",
                    ""
            );
        }

        Path projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path bridgeWorkingDir = projectRoot.resolve(externalMcpProperties.getBridgeWorkingDir()).normalize();
        String cwdValidation = validateCwd(request.cwd(), projectRoot, bridgeWorkingDir);
        if (cwdValidation != null) {
            return ExternalMcpCallResponse.error("SECURITY_VIOLATION", cwdValidation, "", "");
        }

        String envValidation = validateEnv(request.env(), resolvedServer.config().getEnv());
        if (envValidation != null) {
            return ExternalMcpCallResponse.error("SECURITY_VIOLATION", envValidation, "", "");
        }

        String toolKey = resolvedServer.serverId() + "::" + request.toolName().trim();
        if (!healthStore.allowCall(toolKey)) {
            return ExternalMcpCallResponse.error(
                    "CIRCUIT_OPEN",
                    "external mcp circuit open for " + request.toolName(),
                    "",
                    ""
            );
        }

        try {
            ensureBridgeReady(projectRoot, bridgeWorkingDir);
        } catch (Exception e) {
            markFailure(toolKey);
            return ExternalMcpCallResponse.error(
                    "BRIDGE_INIT_ERROR",
                    "failed to initialize mcp bridge: " + summarizeText(e.getMessage()),
                    "",
                    ""
            );
        }

        Path scriptPath = projectRoot.resolve(externalMcpProperties.getBridgeScriptPath()).normalize();
        if (!Files.exists(scriptPath)) {
            markFailure(toolKey);
            return ExternalMcpCallResponse.error(
                    "BRIDGE_SCRIPT_NOT_FOUND",
                    "bridge script not found: " + scriptPath,
                    "",
                    ""
            );
        }

        List<String> command = new ArrayList<>();
        command.add("node");
        command.add(scriptPath.toString());
        command.add("--serverCommand");
        command.add(resolvedServer.command());
        command.add("--tool");
        command.add(request.toolName());
        command.add("--args");
        command.add(serializeArgs(request.arguments()));
        command.add("--timeoutMs");
        command.add(String.valueOf(resolveTimeoutMs(request.timeoutSeconds())));

        if (StrUtil.isNotBlank(request.cwd())) {
            command.add("--cwd");
            command.add(Paths.get(request.cwd()).toAbsolutePath().normalize().toString());
        }

        Map<String, String> env = request.env() == null ? Map.of() : request.env();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (StrUtil.isBlank(entry.getKey())) {
                continue;
            }
            command.add("--env");
            command.add(entry.getKey() + "=" + StrUtil.blankToDefault(entry.getValue(), ""));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot.toFile());

        try {
            Process process = processBuilder.start();
            int timeoutSeconds = Math.max(5, request.timeoutSeconds() + 3);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                markFailure(toolKey);
                return ExternalMcpCallResponse.error("TIMEOUT", "external mcp process timeout", "", "");
            }

            String stdout = readAsUtf8(process.getInputStream());
            String stderr = readAsUtf8(process.getErrorStream());
            int exitCode = process.exitValue();

            Map<String, Object> payload = parseJson(stdout);
            boolean success = Boolean.TRUE.equals(payload.get("success"));
            if (success) {
                Map<String, Object> result = extractMap(payload.get("result"));
                String text = extractTextResult(result);
                markSuccess(toolKey);
                return ExternalMcpCallResponse.ok(text, result, summarizeText(stdout));
            }

            String errorCode = StrUtil.blankToDefault(stringValue(payload.get("errorCode")), "EXECUTION_ERROR");
            String errorMessage = StrUtil.blankToDefault(stringValue(payload.get("errorMessage")), stderr);
            if (StrUtil.containsIgnoreCase(errorMessage, "No connection to browser extension")) {
                errorCode = "BRIDGE_EXTENSION_NOT_CONNECTED";
            }
            if (exitCode == 124) {
                errorCode = "TIMEOUT";
            }
            if (isTransientError(errorCode)) {
                markFailure(toolKey);
            } else {
                markNeutral(toolKey);
            }
            return ExternalMcpCallResponse.error(
                    normalizeErrorCode(errorCode),
                    summarizeText(errorMessage),
                    summarizeText(stdout),
                    summarizeText(stderr)
            );
        } catch (Exception e) {
            markFailure(toolKey);
            return ExternalMcpCallResponse.error(
                    "PROCESS_ERROR",
                    "failed to execute external mcp: " + summarizeText(e.getMessage()),
                    "",
                    ""
            );
        }
    }

    private void ensureBridgeReady(Path projectRoot, Path workingDir) throws IOException, InterruptedException {
        if (bridgeReady.get()) {
            return;
        }
        if (!externalMcpProperties.isAutoInstallBridgeDeps()) {
            bridgeReady.set(true);
            return;
        }

        installLock.lock();
        try {
            if (bridgeReady.get()) {
                return;
            }
            Path sdkMarker = workingDir.resolve("node_modules/@modelcontextprotocol/sdk/package.json");
            if (!Files.exists(sdkMarker)) {
                log.info("Installing MCP bridge dependencies under {}", workingDir);
                ProcessBuilder installPb = new ProcessBuilder("npm", "install", "--silent");
                installPb.directory(workingDir.toFile());
                Process installProcess = installPb.start();
                boolean finished = installProcess.waitFor(
                        Math.max(30, externalMcpProperties.getBridgeInstallTimeoutSeconds()),
                        TimeUnit.SECONDS
                );
                if (!finished) {
                    installProcess.destroyForcibly();
                    throw new IllegalStateException("bridge npm install timeout");
                }
                if (installProcess.exitValue() != 0) {
                    String stderr = readAsUtf8(installProcess.getErrorStream());
                    throw new IllegalStateException("bridge npm install failed: " + summarizeText(stderr));
                }
            }
            bridgeReady.set(true);
        } finally {
            installLock.unlock();
        }
    }

    private ResolvedServer resolveAllowedServer(String serverCommand) {
        String normalized = StrUtil.blankToDefault(serverCommand, "").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String obsidianCommand = composeServerCommand(externalMcpProperties.getObsidian());
        if (normalized.equals(obsidianCommand)) {
            return new ResolvedServer("obsidian", externalMcpProperties.getObsidian(), obsidianCommand);
        }
        String browserCommand = composeServerCommand(externalMcpProperties.getBrowsermcp());
        if (normalized.equals(browserCommand)) {
            return new ResolvedServer("browsermcp", externalMcpProperties.getBrowsermcp(), browserCommand);
        }
        return null;
    }

    private String validateCwd(String cwd, Path projectRoot, Path bridgeWorkingDir) {
        if (StrUtil.isBlank(cwd)) {
            return null;
        }
        Path normalized = Paths.get(cwd).toAbsolutePath().normalize();
        if (normalized.equals(projectRoot) || normalized.equals(bridgeWorkingDir)) {
            return null;
        }
        return "external mcp cwd is not allowlisted";
    }

    private String validateEnv(Map<String, String> requestedEnv, Map<String, String> configuredEnv) {
        if (requestedEnv == null || requestedEnv.isEmpty()) {
            return null;
        }
        Map<String, String> allowlisted = configuredEnv == null ? Map.of() : configuredEnv;
        for (Map.Entry<String, String> entry : requestedEnv.entrySet()) {
            String key = entry.getKey();
            if (StrUtil.isBlank(key) || !allowlisted.containsKey(key)) {
                return "external mcp env is not allowlisted";
            }
            String expectedValue = StrUtil.blankToDefault(allowlisted.get(key), "");
            String actualValue = StrUtil.blankToDefault(entry.getValue(), "");
            if (!Objects.equals(expectedValue, actualValue)) {
                return "external mcp env value is not allowlisted";
            }
        }
        return null;
    }

    private String composeServerCommand(ExternalMcpProperties.ExternalServerConfig config) {
        if (config == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(StrUtil.blankToDefault(config.getCommand(), "").trim());
        if (config.getArgs() != null) {
            for (String arg : config.getArgs()) {
                if (StrUtil.isBlank(arg)) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(arg.trim());
            }
        }
        return builder.toString().trim();
    }

    private long resolveTimeoutMs(int timeoutSeconds) {
        return Duration.ofSeconds(Math.max(5, timeoutSeconds)).toMillis();
    }

    private String serializeArgs(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJson(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw.trim(), new TypeReference<>() {
            });
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("success", false);
            fallback.put("errorCode", "BRIDGE_INVALID_RESPONSE");
            fallback.put("errorMessage", summarizeText(raw));
            return fallback;
        }
    }

    private Map<String, Object> extractMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return map;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String extractTextResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        Object contentObj = result.get("content");
        if (!(contentObj instanceof List<?> contentList)) {
            return objectToJson(result);
        }

        StringBuilder text = new StringBuilder();
        for (Object each : contentList) {
            if (!(each instanceof Map<?, ?> item)) {
                continue;
            }
            Object type = item.get("type");
            if ("text".equals(type)) {
                Object value = item.get("text");
                if (value != null) {
                    if (!text.isEmpty()) {
                        text.append("\n");
                    }
                    text.append(value);
                }
            }
        }

        if (!text.isEmpty()) {
            return text.toString();
        }
        return objectToJson(result);
    }

    private String objectToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String readAsUtf8(InputStream inputStream) throws IOException {
        try (inputStream; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private boolean isTransientError(String errorCode) {
        String normalized = normalizeErrorCode(errorCode);
        return normalized.contains("TIMEOUT")
                || normalized.contains("PROCESS")
                || normalized.startsWith("BRIDGE_")
                || normalized.contains("429")
                || normalized.contains("503");
    }

    private String normalizeErrorCode(String errorCode) {
        String normalized = StrUtil.blankToDefault(errorCode, "").trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "EXECUTION_ERROR";
        }
        return normalized;
    }

    private void markSuccess(String toolKey) {
        healthStore.markSuccess(toolKey);
    }

    private void markFailure(String toolKey) {
        healthStore.markFailure(toolKey);
    }

    private void markNeutral(String toolKey) {
        healthStore.markNeutral(toolKey);
    }

    private String summarizeText(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        String normalized = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...(truncated)";
    }

    private record ResolvedServer(String serverId,
                                  ExternalMcpProperties.ExternalServerConfig config,
                                  String command) {
    }
}
