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

package com.nageoffer.ai.ragent.rag.core.mcp.executor.obsidian;

import com.nageoffer.ai.ragent.rag.config.ObsidianProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Obsidian CLI 进程调用封装
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObsidianCliExecutor {

    private final ObsidianProperties properties;

    /**
     * 执行 Obsidian CLI 命令
     *
     * @param command CLI 子命令（如 read、search、files 等）
     * @param args    额外参数列表
     * @return CLI 执行结果
     */
    public CliResult execute(String command, List<String> args) {
        List<String> cmdLine = buildCommandLine(command, args);
        log.info("Obsidian CLI 执行: {}", String.join(" ", cmdLine));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.environment().put("LANG", "en_US.UTF-8");
            Process process = pb.start();

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readStream(process));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            return reader.lines().collect(Collectors.joining("\n"));
                        } catch (IOException e) {
                            return "stderr read error: " + e.getMessage();
                        }
                    });

            boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CliResult(-1, "", "CLI 执行超时（" + properties.getTimeoutSeconds() + "s）");
            }

            String stdout = stdoutFuture.join();
            String stderr = filterStderrNoise(stderrFuture.join());
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.warn("Obsidian CLI 非零退出: code={}, stderr={}", exitCode, stderr);
            }

            return new CliResult(exitCode, stdout, stderr);
        } catch (IOException e) {
            log.error("Obsidian CLI 进程启动失败", e);
            return new CliResult(-1, "", "CLI 启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CliResult(-1, "", "CLI 执行被中断");
        }
    }

    private List<String> buildCommandLine(String command, List<String> args) {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(properties.getCliPath());
        cmdLine.add(command);
        cmdLine.add("vault=" + properties.getVaultName());
        if (args != null) {
            cmdLine.addAll(args);
        }
        return cmdLine;
    }

    private String readStream(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "stdout read error: " + e.getMessage();
        }
    }

    private String filterStderrNoise(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String[] lines = stderr.split("\n");
        StringBuilder filtered = new StringBuilder();
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("loading") || lower.contains("out of date") || lower.contains("download")) {
                continue;
            }
            if (!filtered.isEmpty()) {
                filtered.append("\n");
            }
            filtered.append(line);
        }
        return filtered.toString();
    }

    /**
     * CLI 执行结果
     */
    public record CliResult(int exitCode, String stdout, String stderr) {

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
