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

import com.nageoffer.ai.ragent.rag.config.ObsidianProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Agent 写回服务
 * <p>
 * 提供路径白名单校验、原子写入、失败回滚、幂等去重。
 */
@Slf4j
@Service
public class AgentWritebackService {

    private static final String DAILY_NOTE_ROOT = "2-Resource（参考资源）/80_生活记录/DailyNote";
    private static final String SPRING_JOB_ROOT = "1-Information（项目与任务）/202601_春招";

    private final Path vaultRoot;
    private final List<Path> writeAllowedRoots;
    private final MarkdownMutationService markdownMutationService;
    private final Set<String> idempotentHashes = ConcurrentHashMap.newKeySet();

    public AgentWritebackService(ObsidianProperties properties,
                                 MarkdownMutationService markdownMutationService) {
        this.vaultRoot = Path.of(properties.getVaultPath()).normalize();
        this.writeAllowedRoots = List.of(
                vaultRoot.resolve(DAILY_NOTE_ROOT).normalize(),
                vaultRoot.resolve(SPRING_JOB_ROOT).normalize()
        );
        this.markdownMutationService = markdownMutationService;
    }

    public String readAllowedFile(String relativePath) {
        Path target = resolvePath(relativePath, true);
        return readFile(target);
    }

    public String readVaultFile(String pathLike) {
        Path target = resolvePath(pathLike, false);
        return readFile(target);
    }

    public List<String> listAllowedMarkdownFiles(String relativeDir) {
        Path dir = resolvePath(relativeDir, true);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .map(path -> vaultRoot.relativize(path).toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("读取目录失败: {}", dir, e);
            return List.of();
        }
    }

    public WriteOutcome writeFile(String relativePath, String content, String commandDigest) {
        Path target = resolvePath(relativePath, true);
        String normalized = markdownMutationService.normalize(content);

        String opHash = sha256(commandDigest + "|" + target + "|" + sha256(normalized));
        if (!idempotentHashes.add(opHash)) {
            return WriteOutcome.builder()
                    .relativePath(vaultRoot.relativize(target).toString())
                    .absolutePath(target.toString())
                    .changed(false)
                    .skipped(true)
                    .message("幂等去重，已跳过")
                    .build();
        }

        try {
            Files.createDirectories(target.getParent());
            String existing = Files.exists(target)
                    ? Files.readString(target, StandardCharsets.UTF_8)
                    : null;
            existing = markdownMutationService.normalize(existing);
            if (Objects.equals(existing, normalized)) {
                return WriteOutcome.builder()
                        .relativePath(vaultRoot.relativize(target).toString())
                        .absolutePath(target.toString())
                        .changed(false)
                        .skipped(true)
                        .message("内容无变化")
                        .build();
            }

            Path tempFile = Files.createTempFile(target.getParent(), ".agent-write-", ".tmp");
            Files.writeString(tempFile, normalized, StandardCharsets.UTF_8);

            Path backupFile = null;
            try {
                if (Files.exists(target)) {
                    backupFile = target.resolveSibling(target.getFileName() + ".bak." + System.currentTimeMillis());
                    Files.move(target, backupFile, StandardCopyOption.REPLACE_EXISTING);
                }

                try {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignore) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }

                if (backupFile != null) {
                    Files.deleteIfExists(backupFile);
                }

                return WriteOutcome.builder()
                        .relativePath(vaultRoot.relativize(target).toString())
                        .absolutePath(target.toString())
                        .changed(true)
                        .skipped(false)
                        .message("写入成功")
                        .build();
            } catch (Exception writeEx) {
                restoreBackup(target, backupFile);
                Files.deleteIfExists(tempFile);
                throw writeEx;
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入文件失败: " + relativePath, e);
        }
    }

    public String toRelativePath(Path absolutePath) {
        return vaultRoot.relativize(absolutePath.normalize()).toString();
    }

    public String digestCommand(String workflowId, String userId, String rawInput) {
        return sha256((workflowId == null ? "" : workflowId)
                + "|" + (userId == null ? "" : userId)
                + "|" + (rawInput == null ? "" : rawInput));
    }

    public List<String> getWriteAllowedRoots() {
        List<String> result = new ArrayList<>();
        for (Path path : writeAllowedRoots) {
            result.add(path.toString());
        }
        return result;
    }

    private String readFile(Path target) {
        if (!Files.exists(target)) {
            return "";
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + target, e);
        }
    }

    private Path resolvePath(String pathLike, boolean requireWriteAllowed) {
        if (pathLike == null || pathLike.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path raw = Path.of(pathLike);
        Path target = raw.isAbsolute() ? raw.normalize() : vaultRoot.resolve(pathLike).normalize();
        if (!target.startsWith(vaultRoot)) {
            throw new IllegalArgumentException("路径越界: " + pathLike);
        }
        if (requireWriteAllowed && writeAllowedRoots.stream().noneMatch(target::startsWith)) {
            throw new IllegalArgumentException("路径不在写白名单内: " + pathLike);
        }
        return target;
    }

    private void restoreBackup(Path target, Path backupFile) {
        if (backupFile == null) {
            return;
        }
        try {
            if (Files.exists(backupFile)) {
                Files.move(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("回滚备份失败: target={}, backup={}", target, backupFile, ex);
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class WriteOutcome {

        private String relativePath;
        private String absolutePath;
        private boolean changed;
        private boolean skipped;
        private String message;
    }
}
