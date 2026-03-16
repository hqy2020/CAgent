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

package com.openingcloud.ai.ragent.core.parser;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本清理工具类
 * <p>
 * 提供统一的文本清理逻辑，用于文档解析后的文本规范化
 */
public final class TextCleanupUtil {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern STANDALONE_PAGE_NUMBER = Pattern.compile(
            "^\\s*(?:第\\s*\\d+\\s*页|\\d+\\s*/\\s*\\d+|[-—]*\\s*\\d+\\s*[-—]*)\\s*$"
    );

    private TextCleanupUtil() {
    }

    /**
     * 清理文本内容
     * <p>
     * 执行以下清理操作：
     * 1. 移除 BOM 标记（\uFEFF）
     * 2. 移除行尾多余的空格和制表符
     * 3. 压缩连续的空行（3个以上压缩为2个）
     * 4. 去除首尾空白
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return cleanup(text, TextCleanupOptions.defaultOptions());
    }

    /**
     * 清理文本内容（自定义规则）
     *
     * @param text                原始文本
     * @param removeBOM           是否移除 BOM
     * @param trimTrailingSpaces  是否移除行尾空格
     * @param compressEmptyLines  是否压缩空行
     * @param maxConsecutiveLines 最多保留的连续空行数
     * @return 清理后的文本
     */
    public static String cleanup(String text,
                                 boolean removeBOM,
                                 boolean trimTrailingSpaces,
                                 boolean compressEmptyLines,
                                 int maxConsecutiveLines) {
        TextCleanupOptions options = TextCleanupOptions.defaultOptions();
        options.setRemoveBOM(removeBOM);
        options.setTrimTrailingSpaces(trimTrailingSpaces);
        options.setCompressEmptyLines(compressEmptyLines);
        options.setMaxConsecutiveEmptyLines(maxConsecutiveLines);
        return cleanup(text, options);
    }

    /**
     * 使用预设选项执行清理
     */
    public static String cleanup(String text, TextCleanupOptions options) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        TextCleanupOptions resolved = options == null ? TextCleanupOptions.defaultOptions() : mergeWithDefaults(options);
        String result = text;

        if (Boolean.TRUE.equals(resolved.getNormalizeLineEndings())) {
            result = result.replace("\r\n", "\n").replace('\r', '\n');
        }

        if (Boolean.TRUE.equals(resolved.getRemoveBOM())) {
            result = result.replace("\uFEFF", "");
        }

        if (Boolean.TRUE.equals(resolved.getStripControlChars())) {
            result = CONTROL_CHARS.matcher(result).replaceAll("");
        }

        if (Boolean.TRUE.equals(resolved.getNormalizeUnicodeSpaces())) {
            result = normalizeUnicodeSpaces(result);
        }

        if (Boolean.TRUE.equals(resolved.getTrimTrailingSpaces())) {
            result = result.replaceAll("[ \\t]+\\n", "\n");
        }

        if (Boolean.TRUE.equals(resolved.getRemoveStandalonePageNumbers())) {
            result = removeStandalonePageNumbers(result);
        }

        if (Boolean.TRUE.equals(resolved.getMergeWrappedLines())) {
            result = mergeWrappedLines(result);
        }

        if (Boolean.TRUE.equals(resolved.getCompressEmptyLines())
                && resolved.getMaxConsecutiveEmptyLines() != null
                && resolved.getMaxConsecutiveEmptyLines() > 0) {
            String pattern = "\\n{" + (resolved.getMaxConsecutiveEmptyLines() + 1) + ",}";
            String replacement = "\n".repeat(resolved.getMaxConsecutiveEmptyLines());
            result = result.replaceAll(pattern, replacement);
        }

        return result.trim();
    }

    private static TextCleanupOptions mergeWithDefaults(TextCleanupOptions options) {
        TextCleanupOptions defaults = switch (options.getProfile()) {
            case TextCleanupOptions.PROFILE_MARKDOWN_STANDARD -> TextCleanupOptions.markdownStandard();
            case TextCleanupOptions.PROFILE_PDF_STANDARD -> TextCleanupOptions.pdfStandard();
            default -> TextCleanupOptions.defaultOptions();
        };
        if (options.getRemoveBOM() != null) {
            defaults.setRemoveBOM(options.getRemoveBOM());
        }
        if (options.getNormalizeLineEndings() != null) {
            defaults.setNormalizeLineEndings(options.getNormalizeLineEndings());
        }
        if (options.getStripControlChars() != null) {
            defaults.setStripControlChars(options.getStripControlChars());
        }
        if (options.getNormalizeUnicodeSpaces() != null) {
            defaults.setNormalizeUnicodeSpaces(options.getNormalizeUnicodeSpaces());
        }
        if (options.getTrimTrailingSpaces() != null) {
            defaults.setTrimTrailingSpaces(options.getTrimTrailingSpaces());
        }
        if (options.getCompressEmptyLines() != null) {
            defaults.setCompressEmptyLines(options.getCompressEmptyLines());
        }
        if (options.getMaxConsecutiveEmptyLines() != null) {
            defaults.setMaxConsecutiveEmptyLines(options.getMaxConsecutiveEmptyLines());
        }
        if (options.getRemoveStandalonePageNumbers() != null) {
            defaults.setRemoveStandalonePageNumbers(options.getRemoveStandalonePageNumbers());
        }
        if (options.getMergeWrappedLines() != null) {
            defaults.setMergeWrappedLines(options.getMergeWrappedLines());
        }
        return defaults;
    }

    private static String normalizeUnicodeSpaces(String text) {
        return text
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace('\u3000', ' ');
    }

    private static String removeStandalonePageNumbers(String text) {
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (STANDALONE_PAGE_NUMBER.matcher(line).matches()) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    private static String mergeWrappedLines(String text) {
        String[] lines = text.split("\n", -1);
        List<String> merged = new ArrayList<>(lines.length);
        StringBuilder current = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.stripTrailing();
            if (!StringUtils.hasText(line)) {
                flushMergedLine(merged, current);
                merged.add("");
                continue;
            }

            if (current.isEmpty()) {
                current.append(line.trim());
                continue;
            }

            if (shouldMerge(current.toString(), line)) {
                appendMergedLine(current, line);
            } else {
                flushMergedLine(merged, current);
                current.append(line.trim());
            }
        }

        flushMergedLine(merged, current);
        return String.join("\n", merged);
    }

    private static boolean shouldMerge(String previous, String next) {
        String prev = previous == null ? "" : previous.stripTrailing();
        String after = next == null ? "" : next.stripLeading();
        if (!StringUtils.hasText(prev) || !StringUtils.hasText(after)) {
            return false;
        }
        if (isMarkdownFence(prev) || isMarkdownFence(after)) {
            return false;
        }
        if (isHeadingLike(prev) || isHeadingLike(after)) {
            return false;
        }
        if (isListLike(prev) || isListLike(after)) {
            return false;
        }
        if (isTableLike(prev) || isTableLike(after)) {
            return false;
        }
        if (endsWithSentenceBoundary(prev)) {
            return false;
        }
        return true;
    }

    private static void appendMergedLine(StringBuilder current, String next) {
        String existing = current.toString();
        String line = next.stripLeading();
        if (existing.endsWith("-") && !existing.endsWith("--")) {
            current.setLength(existing.length() - 1);
            current.append(line);
            return;
        }

        if (needsSpace(existing, line)) {
            current.append(' ');
        }
        current.append(line);
    }

    private static boolean needsSpace(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        char leftChar = left.charAt(left.length() - 1);
        char rightChar = right.charAt(0);
        return Character.isLetterOrDigit(leftChar) && Character.isLetterOrDigit(rightChar);
    }

    private static void flushMergedLine(List<String> merged, StringBuilder current) {
        if (current.isEmpty()) {
            return;
        }
        merged.add(current.toString().trim());
        current.setLength(0);
    }

    private static boolean endsWithSentenceBoundary(String line) {
        return line.endsWith("。")
                || line.endsWith("！")
                || line.endsWith("？")
                || line.endsWith(".")
                || line.endsWith("!")
                || line.endsWith("?")
                || line.endsWith(":")
                || line.endsWith("：")
                || line.endsWith(";")
                || line.endsWith("；");
    }

    private static boolean isHeadingLike(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("#")
                || trimmed.matches("^\\d+(?:\\.\\d+)*\\s+.+$")
                || trimmed.matches("^[一二三四五六七八九十]+、.+$")
                || trimmed.matches("^第[一二三四五六七八九十\\d]+[章节篇部].*$");
    }

    private static boolean isListLike(String line) {
        String trimmed = line.trim();
        return trimmed.matches("^[-*+]\\s+.+$")
                || trimmed.matches("^\\d+[.)、]\\s+.+$")
                || trimmed.matches("^[a-zA-Z][.)]\\s+.+$");
    }

    private static boolean isTableLike(String line) {
        String trimmed = line.trim();
        return trimmed.contains("|") || trimmed.contains("\t");
    }

    private static boolean isMarkdownFence(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }
}
