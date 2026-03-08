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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Obsidian 文件系统操作封装（替代原 CLI 进程调用）
 *
 * <p>直接通过 java.nio.file 操作 vault 目录，支持 read/search/files/folders/create/append/prepend/daily:append/delete 操作。
 * 保持 CliResult 返回接口不变，所有调用方零改动。
 */
@Slf4j
@Component
public class ObsidianCliExecutor {

    private enum AccessMode {
        READ,
        WRITE
    }

    private final ObsidianProperties properties;
    private final ObsidianExternalMcpGateway externalGateway;

    private static final String DAILY_NOTE_PATH_PATTERN = "2-Resource（参考资源）/80_生活记录/DailyNote/日记";
    private static final int SEARCH_CONTEXT_LINES = 2;
    private static final Set<String> BLOCKED_SEGMENTS = Set.of(
            ".obsidian", ".trash", ".git", "Library", "System", "Applications", "private", "etc", "var"
    );
    private static final Set<String> WRITE_ALLOWED_TOP_LEVELS = Set.of(
            "0-Inbox", "1-Inbox", "1-Projects", "2-Resource（参考资源）", "3-Knowledge",
            "4-Areas", "5-Output", "5-Archive", "6-Attachments", "7-Assets", "8-Templates"
    );

    @Autowired
    public ObsidianCliExecutor(ObsidianProperties properties,
                               ObsidianExternalMcpGateway externalGateway) {
        this.properties = properties;
        this.externalGateway = externalGateway;
    }

    /**
     * 测试专用构造（仅本地模式）。
     */
    public ObsidianCliExecutor(ObsidianProperties properties) {
        this.properties = properties;
        this.externalGateway = null;
    }

    /**
     * 执行 Obsidian 操作命令
     *
     * @param command 操作子命令（如 read、search、files、folders、create、append、prepend、daily:append、delete）
     * @param args    额外参数列表，格式为 key=value
     * @return 操作结果
     */
    public CliResult execute(String command, List<String> args) {
        Map<String, String> params = parseArgs(args);
        log.info("Obsidian 文件操作: command={}, params={}", command, summarizeParams(params));

        ObsidianExternalMcpGateway.ExternalExecuteResult externalResult = tryExternal(command, params);
        if (externalResult.success()) {
            return new CliResult(0, externalResult.textResult(), "");
        }
        if (externalGateway != null && externalGateway.externalOnly() && externalResult.attempted()) {
            return new CliResult(1, "", "EXTERNAL_MCP_ERROR: " + externalResult.errorMessage());
        }

        try {
            CliResult localResult = switch (command) {
                case "read" -> doRead(params);
                case "search" -> doSearch(params, false);
                case "search:context" -> doSearch(params, true);
                case "files" -> doListFiles(params);
                case "folders" -> doListFolders(params);
                case "create" -> doCreate(params);
                case "append" -> doAppend(params);
                case "prepend" -> doPrepend(params);
                case "daily:append" -> doDailyAppend(params);
                case "delete" -> doDelete(params);
                case "replace" -> doReplace(params);
                default -> new CliResult(1, "", "不支持的命令: " + command);
            };

            if (localResult.isSuccess() && externalResult.attempted() && !externalResult.success()) {
                String fallbackLine = "[fallback] 外部 Obsidian MCP 调用失败，已切换本地文件模式。"
                        + " error=" + externalResult.errorCode();
                String merged = localResult.stdout().isBlank()
                        ? fallbackLine
                        : localResult.stdout() + "\n" + fallbackLine;
                return new CliResult(localResult.exitCode(), merged, localResult.stderr());
            }
            return localResult;
        } catch (SecurityException e) {
            log.warn("Obsidian 文件操作被安全策略阻断: command={}, message={}", command, e.getMessage());
            return new CliResult(1, "", "SECURITY_VIOLATION: " + e.getMessage());
        } catch (IOException e) {
            log.error("Obsidian 文件操作失败: command={}", command, e);
            return new CliResult(1, "", "文件操作失败: " + e.getMessage());
        }
    }

    private ObsidianExternalMcpGateway.ExternalExecuteResult tryExternal(String command, Map<String, String> params) {
        if (externalGateway == null) {
            return ObsidianExternalMcpGateway.ExternalExecuteResult.skipped();
        }
        try {
            return externalGateway.tryExecute(command, params);
        } catch (Exception e) {
            log.warn("External Obsidian MCP execution failed, fallback to local mode. command={}", command, e);
            return ObsidianExternalMcpGateway.ExternalExecuteResult.failed("EXTERNAL_EXCEPTION", e.getMessage());
        }
    }

    private Map<String, Object> summarizeParams(Map<String, String> params) {
        Map<String, Object> summary = new HashMap<>();
        if (params == null || params.isEmpty()) {
            return summary;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("content".equalsIgnoreCase(key)
                    || "oldContent".equalsIgnoreCase(key)
                    || "newContent".equalsIgnoreCase(key)
                    || "url".equalsIgnoreCase(key)
                    || "sourceUrl".equalsIgnoreCase(key)) {
                summary.put(key, value == null ? "[MASKED length=0]" : "[MASKED length=" + value.length() + "]");
            } else {
                summary.put(key, value);
            }
        }
        return summary;
    }

    // ===================== 命令实现 =====================

    private CliResult doRead(Map<String, String> params) throws IOException {
        Path filePath = resolveNotePath(params, AccessMode.READ);
        if (filePath == null) {
            return new CliResult(1, "", "必须提供 file 或 path 参数");
        }
        if (!Files.exists(filePath)) {
            return new CliResult(1, "", "笔记不存在: " + filePath);
        }
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return new CliResult(0, content, "");
    }

    private CliResult doSearch(Map<String, String> params, boolean withContext) throws IOException {
        String query = params.get("query");
        if (query == null || query.isBlank()) {
            return new CliResult(1, "", "必须提供 query 参数");
        }

        String searchPath = params.get("path");
        int limit = parseIntParam(params, "limit", 10);
        Path searchRoot = searchPath != null ? resolveDirectoryPath(searchPath, AccessMode.READ) : getVaultPath();

        if (!Files.exists(searchRoot)) {
            return new CliResult(1, "", "搜索路径不存在: " + searchRoot);
        }

        List<String> results = new ArrayList<>();
        String queryLower = query.toLowerCase();

        Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(searchRoot) && dir.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (results.size() >= limit) {
                    return FileVisitResult.TERMINATE;
                }
                if (!file.toString().endsWith(".md")) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    String relativePath = getVaultPath().relativize(file).toString();

                    for (int i = 0; i < lines.size(); i++) {
                        if (results.size() >= limit) {
                            break;
                        }
                        if (lines.get(i).toLowerCase().contains(queryLower)) {
                            if (withContext) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("📄 ").append(relativePath).append(" (line ").append(i + 1).append(")\n");
                                int start = Math.max(0, i - SEARCH_CONTEXT_LINES);
                                int end = Math.min(lines.size(), i + SEARCH_CONTEXT_LINES + 1);
                                for (int j = start; j < end; j++) {
                                    sb.append(j == i ? ">>> " : "    ").append(lines.get(j)).append("\n");
                                }
                                results.add(sb.toString());
                            } else {
                                results.add(relativePath + ":" + (i + 1) + ": " + lines.get(i).trim());
                            }
                        }
                    }
                } catch (IOException e) {
                    log.debug("读取文件失败，跳过: {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (results.isEmpty()) {
            return new CliResult(0, "未找到包含「" + query + "」的笔记", "");
        }
        return new CliResult(0, String.join("\n", results), "");
    }

    private CliResult doListFiles(Map<String, String> params) throws IOException {
        String folder = params.get("folder");
        String ext = params.getOrDefault("ext", "md");
        Path root = folder != null ? resolveDirectoryPath(folder, AccessMode.READ) : getVaultPath();

        if (!Files.exists(root)) {
            return new CliResult(1, "", "目录不存在: " + root);
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("." + ext))
                    .filter(p -> !isHiddenPath(p))
                    .forEach(p -> files.add(getVaultPath().relativize(p).toString()));
        }
        return new CliResult(0, String.join("\n", files), "");
    }

    private CliResult doListFolders(Map<String, String> params) throws IOException {
        String folder = params.get("folder");
        Path root = folder != null ? resolveDirectoryPath(folder, AccessMode.READ) : getVaultPath();

        if (!Files.exists(root)) {
            return new CliResult(1, "", "目录不存在: " + root);
        }

        List<String> folders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .filter(p -> !isHiddenPath(p))
                    .forEach(p -> folders.add(getVaultPath().relativize(p).toString()));
        }
        return new CliResult(0, String.join("\n", folders), "");
    }

    private CliResult doCreate(Map<String, String> params) throws IOException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return new CliResult(1, "", "必须提供 name 参数");
        }

        String path = params.get("path");
        String content = params.getOrDefault("content", "");

        Path targetDir = path != null ? resolveDirectoryPath(path, AccessMode.WRITE) : getVaultPath();
        Files.createDirectories(targetDir);

        String fileName = ensureMarkdownSuffix(validateFileName(name));
        Path filePath = targetDir.resolve(fileName).normalize();
        ensureInsideVault(filePath);
        ensureWriteAllowed(filePath);

        if (Files.exists(filePath)) {
            return new CliResult(1, "", "笔记已存在: " + getVaultPath().relativize(filePath));
        }

        Files.writeString(filePath, content, StandardCharsets.UTF_8);
        return new CliResult(0, "已创建笔记: " + getVaultPath().relativize(filePath), "");
    }

    private CliResult doAppend(Map<String, String> params) throws IOException {
        String content = params.get("content");
        if (content == null) {
            return new CliResult(1, "", "必须提供 content 参数");
        }

        Path filePath = resolveNotePath(params, AccessMode.WRITE);
        if (filePath == null) {
            return new CliResult(1, "", "必须提供 file 或 path 参数");
        }
        if (!Files.exists(filePath)) {
            return new CliResult(1, "", "笔记不存在: " + filePath);
        }

        String existing = Files.readString(filePath, StandardCharsets.UTF_8);
        String separator = existing.endsWith("\n") ? "" : "\n";
        Files.writeString(filePath, existing + separator + content + "\n", StandardCharsets.UTF_8);
        CliResult verifyResult = verifyWrite(filePath, separator + content + "\n", WritePosition.END, "追加");
        if (verifyResult != null) {
            return verifyResult;
        }
        return new CliResult(0, "已追加内容到: " + getVaultPath().relativize(filePath), "");
    }

    private CliResult doPrepend(Map<String, String> params) throws IOException {
        String content = params.get("content");
        if (content == null) {
            return new CliResult(1, "", "必须提供 content 参数");
        }

        Path filePath = resolveNotePath(params, AccessMode.WRITE);
        if (filePath == null) {
            return new CliResult(1, "", "必须提供 file 或 path 参数");
        }
        if (!Files.exists(filePath)) {
            return new CliResult(1, "", "笔记不存在: " + filePath);
        }

        String existing = Files.readString(filePath, StandardCharsets.UTF_8);
        Files.writeString(filePath, content + "\n" + existing, StandardCharsets.UTF_8);
        CliResult verifyResult = verifyWrite(filePath, content + "\n", WritePosition.START, "前插");
        if (verifyResult != null) {
            return verifyResult;
        }
        return new CliResult(0, "已前插内容到: " + getVaultPath().relativize(filePath), "");
    }

    private CliResult doDailyAppend(Map<String, String> params) throws IOException {
        String content = params.get("content");
        if (content == null) {
            return new CliResult(1, "", "必须提供 content 参数");
        }

        String dateStr = params.get("date");
        LocalDate date = parseDateParam(dateStr);
        String dateFileName = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".md";
        Path dailyPath = resolveDirectoryPath(DAILY_NOTE_PATH_PATTERN, AccessMode.WRITE).resolve(dateFileName).normalize();
        ensureInsideVault(dailyPath);
        ensureWriteAllowed(dailyPath);

        Files.createDirectories(dailyPath.getParent());

        if (!Files.exists(dailyPath)) {
            String header = "# " + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "\n\n";
            Files.writeString(dailyPath, header + content + "\n", StandardCharsets.UTF_8);
            CliResult verifyResult = verifyWrite(dailyPath, header + content + "\n", WritePosition.START, "创建日记并写入");
            if (verifyResult != null) {
                return verifyResult;
            }
            return new CliResult(0, "已创建日记并写入内容: " + dateFileName, "");
        }

        String existing = Files.readString(dailyPath, StandardCharsets.UTF_8);
        String separator = existing.endsWith("\n") ? "" : "\n";
        Files.writeString(dailyPath, existing + separator + content + "\n", StandardCharsets.UTF_8);
        CliResult verifyResult = verifyWrite(dailyPath, separator + content + "\n", WritePosition.END, "日记追加");
        if (verifyResult != null) {
            return verifyResult;
        }
        return new CliResult(0, "已追加内容到日记: " + dateFileName, "");
    }

    private CliResult doDelete(Map<String, String> params) throws IOException {
        Path filePath = resolveNotePath(params, AccessMode.WRITE);
        if (filePath == null) {
            return new CliResult(1, "", "必须提供 file 或 path 参数");
        }
        if (!Files.exists(filePath)) {
            return new CliResult(1, "", "笔记不存在: " + filePath);
        }

        boolean permanent = "true".equalsIgnoreCase(params.get("permanent"));
        String relativePath = getVaultPath().relativize(filePath).toString();

        if (permanent) {
            Files.delete(filePath);
            return new CliResult(0, "已永久删除笔记: " + relativePath, "");
        } else {
            Path trashDir = getVaultPath().resolve(".trash");
            Files.createDirectories(trashDir);
            Path trashPath = trashDir.resolve(filePath.getFileName());
            Files.move(filePath, trashPath);
            return new CliResult(0, "已移入回收站: " + relativePath, "");
        }
    }

    private CliResult doReplace(Map<String, String> params) throws IOException {
        Path filePath = resolveNotePath(params, AccessMode.WRITE);
        if (filePath == null) {
            return new CliResult(1, "", "必须提供 file 或 path 参数");
        }
        if (!Files.exists(filePath)) {
            return new CliResult(1, "", "笔记不存在: " + filePath);
        }

        String oldContent = params.get("oldContent");
        String newContent = params.get("newContent");
        if (oldContent == null || oldContent.isBlank()) {
            return new CliResult(1, "", "必须提供 oldContent 参数");
        }
        if (newContent == null) {
            return new CliResult(1, "", "必须提供 newContent 参数");
        }

        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        if (!content.contains(oldContent)) {
            return new CliResult(1, "", "未在笔记中找到要替换的内容: " + oldContent);
        }

        int count = countOccurrences(content, oldContent);
        String replaced = content.replace(oldContent, newContent);
        Files.writeString(filePath, replaced, StandardCharsets.UTF_8);
        return new CliResult(0, "替换完成，共替换 " + count + " 处。文件: " + getVaultPath().relativize(filePath), "");
    }

    // ===================== 工具方法 =====================

    private Path getVaultPath() {
        return Path.of(properties.getVaultPath()).toAbsolutePath().normalize();
    }

    private Path resolveNotePath(Map<String, String> params) {
        return resolveNotePath(params, AccessMode.READ);
    }

    private Path resolveNotePath(Map<String, String> params, AccessMode accessMode) {
        String path = params.get("path");
        String file = params.get("file");

        if (path != null && !path.isBlank()) {
            return resolveFilePath(path, accessMode);
        }
        if (file != null && !file.isBlank()) {
            String normalizedFile = ensureMarkdownSuffix(validateFileName(file));
            return findFileByName(normalizedFile);
        }
        return null;
    }

    private Path findFileByName(String fileName) {
        try (Stream<Path> stream = Files.walk(getVaultPath())) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .filter(p -> !isHiddenPath(p))
                    .findFirst()
                    .orElse(resolveFilePath(fileName, AccessMode.READ));
        } catch (IOException e) {
            log.warn("搜索文件失败: {}", fileName, e);
            return resolveFilePath(fileName, AccessMode.READ);
        }
    }

    private Map<String, String> parseArgs(List<String> args) {
        Map<String, String> params = new HashMap<>();
        if (args == null) {
            return params;
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0) {
                params.put(arg.substring(0, eq), arg.substring(eq + 1));
            }
        }
        return params;
    }

    private int parseIntParam(Map<String, String> params, String key, int defaultVal) {
        String val = params.get(key);
        if (val == null) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private boolean isHiddenPath(Path path) {
        Path relative = getVaultPath().relativize(path);
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private Path resolveFilePath(String rawPath, AccessMode accessMode) {
        return resolveRelativePath(rawPath, true, accessMode);
    }

    private Path resolveDirectoryPath(String rawPath, AccessMode accessMode) {
        return resolveRelativePath(rawPath, false, accessMode);
    }

    private Path resolveRelativePath(String rawPath, boolean markdownFile, AccessMode accessMode) {
        String sanitized = sanitizePathText(rawPath);
        Path relative = Path.of(sanitized).normalize();
        if (relative.isAbsolute() || sanitized.startsWith("/")) {
            throw new SecurityException("禁止使用绝对路径");
        }
        if (relative.startsWith("..")) {
            throw new SecurityException("禁止访问 vault 外路径");
        }
        validateRelativePath(relative, accessMode);
        if (markdownFile && !relative.toString().endsWith(".md")) {
            relative = Path.of(relative.toString() + ".md").normalize();
        }
        Path resolved = getVaultPath().resolve(relative).normalize();
        ensureInsideVault(resolved);
        if (accessMode == AccessMode.WRITE) {
            ensureWriteAllowed(resolved);
        }
        return resolved;
    }

    private String sanitizePathText(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new SecurityException("路径不能为空");
        }
        String sanitized = rawPath.trim().replace("\\", "/");
        if (sanitized.contains("\0")) {
            throw new SecurityException("路径包含非法字符");
        }
        return sanitized;
    }

    private void validateRelativePath(Path relative, AccessMode accessMode) {
        if (relative.getNameCount() == 0) {
            return;
        }
        for (Path part : relative) {
            String segment = part.toString();
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if (segment.startsWith(".")) {
                throw new SecurityException("禁止访问隐藏目录: " + segment);
            }
            if (BLOCKED_SEGMENTS.contains(segment)) {
                throw new SecurityException("禁止访问系统目录: " + segment);
            }
        }
        if (accessMode == AccessMode.WRITE && relative.getNameCount() > 1) {
            String topLevel = relative.getName(0).toString();
            if (!WRITE_ALLOWED_TOP_LEVELS.contains(topLevel)) {
                throw new SecurityException("写入路径不在允许白名单内: " + topLevel);
            }
        }
    }

    private void ensureInsideVault(Path resolved) {
        if (!resolved.startsWith(getVaultPath())) {
            throw new SecurityException("禁止访问 vault 外路径");
        }
    }

    private void ensureWriteAllowed(Path resolved) {
        Path relative = getVaultPath().relativize(resolved);
        if (relative.getNameCount() <= 1) {
            return;
        }
        String topLevel = relative.getName(0).toString();
        if (!WRITE_ALLOWED_TOP_LEVELS.contains(topLevel)) {
            throw new SecurityException("写入路径不在允许白名单内: " + topLevel);
        }
    }

    private String validateFileName(String rawName) {
        String sanitized = sanitizePathText(rawName);
        if (sanitized.contains("/")) {
            throw new SecurityException("file 参数仅允许文件名，请改用 path 参数");
        }
        if (sanitized.equals(".") || sanitized.equals("..") || sanitized.startsWith(".")) {
            throw new SecurityException("禁止访问隐藏或非法文件名");
        }
        return sanitized;
    }

    private String ensureMarkdownSuffix(String filename) {
        if (filename == null || filename.isBlank()) {
            return filename;
        }
        return filename.endsWith(".md") ? filename : filename + ".md";
    }

    private LocalDate parseDateParam(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDate.now();
        }
        String trimmed = dateStr.trim();
        if ("今天".equals(trimmed) || "today".equalsIgnoreCase(trimmed)) {
            return LocalDate.now();
        }
        if ("昨天".equals(trimmed) || "yesterday".equalsIgnoreCase(trimmed)) {
            return LocalDate.now().minusDays(1);
        }
        if ("明天".equals(trimmed) || "tomorrow".equalsIgnoreCase(trimmed)) {
            return LocalDate.now().plusDays(1);
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (Exception e) {
            log.warn("无法解析日期参数 '{}', 使用今天", dateStr);
            return LocalDate.now();
        }
    }

    private CliResult verifyWrite(Path filePath, String expectedSegment, WritePosition position, String operation)
            throws IOException {
        if (expectedSegment == null || expectedSegment.isEmpty()) {
            return null;
        }
        String persisted = readFileForVerification(filePath);
        boolean matched = switch (position) {
            case START -> persisted.startsWith(expectedSegment);
            case END -> persisted.endsWith(expectedSegment);
            case CONTAINS -> persisted.contains(expectedSegment);
        };
        if (matched) {
            return null;
        }
        String relativePath = getVaultPath().relativize(filePath).toString();
        return new CliResult(1, "", "WRITE_VERIFY_FAILED: " + operation + "后校验失败，目标文件: " + relativePath);
    }

    protected String readFileForVerification(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 操作结果
     */
    public record CliResult(int exitCode, String stdout, String stderr) {

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private enum WritePosition {
        START,
        END,
        CONTAINS
    }
}
