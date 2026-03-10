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

package com.openingcloud.ai.ragent.rag.agent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构化改写服务
 */
@Service
public class MarkdownMutationService {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+.*$");
    private static final Pattern TABLE_LINE_PATTERN = Pattern.compile("^\\|.*\\|$");

    public String upsertFrontmatterValues(String markdown, Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return normalize(markdown);
        }
        String content = normalize(markdown);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            content = upsertFrontmatterValue(content, entry.getKey(), entry.getValue());
        }
        return content;
    }

    public String upsertFrontmatterValue(String markdown, String key, String value) {
        if (key == null || key.isBlank()) {
            return normalize(markdown);
        }
        String content = normalize(markdown);
        List<String> lines = toLines(content);
        if (!lines.isEmpty() && "---".equals(lines.get(0).trim())) {
            int end = findFrontmatterEnd(lines);
            if (end > 0) {
                String prefix = key + ":";
                for (int i = 1; i < end; i++) {
                    String line = lines.get(i);
                    if (line.startsWith(prefix) || line.matches("^" + Pattern.quote(key) + "\\s*:.*$")) {
                        lines.set(i, key + ": " + formatFrontmatterValue(value));
                        return joinLines(lines);
                    }
                }
                lines.add(end, key + ": " + formatFrontmatterValue(value));
                return joinLines(lines);
            }
        }

        List<String> result = new ArrayList<>();
        result.add("---");
        result.add(key + ": " + formatFrontmatterValue(value));
        result.add("---");
        if (!lines.isEmpty()) {
            result.add("");
            result.addAll(lines);
        }
        return joinLines(result);
    }

    public String upsertInlineField(String markdown, String fieldName, String value) {
        if (fieldName == null || fieldName.isBlank()) {
            return normalize(markdown);
        }
        String content = normalize(markdown);
        Pattern pattern = Pattern.compile("\\[" + Pattern.quote(fieldName) + "::[^\\]]*\\]");
        Matcher matcher = pattern.matcher(content);
        String replacement = "[" + fieldName + "::" + (value == null ? "" : value) + "]";
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        return content;
    }

    public String replaceSection(String markdown, String heading, String body) {
        String content = normalize(markdown);
        List<String> lines = toLines(content);
        SectionRange range = locateSection(lines, heading);
        List<String> bodyLines = normalizeBody(body);

        if (range == null) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add(heading);
            lines.add("");
            lines.addAll(bodyLines);
            return joinLines(trimTrailingBlankLines(lines, 0));
        }

        List<String> result = new ArrayList<>();
        result.addAll(lines.subList(0, range.contentStart));
        if (!result.isEmpty() && !result.get(result.size() - 1).isBlank()) {
            result.add("");
        }
        result.addAll(bodyLines);
        if (range.end < lines.size() && !result.isEmpty() && !result.get(result.size() - 1).isBlank()) {
            result.add("");
        }
        result.addAll(lines.subList(range.end, lines.size()));
        return joinLines(trimTrailingBlankLines(result, 0));
    }

    public String appendChecklistUnderHeading(String markdown, String heading, String taskLine) {
        if (taskLine == null || taskLine.isBlank()) {
            return normalize(markdown);
        }
        String content = normalize(markdown);
        List<String> lines = toLines(content);
        SectionRange range = locateSection(lines, heading);

        if (range == null) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add(heading);
            lines.add(taskLine);
            return joinLines(lines);
        }

        for (int i = range.contentStart; i < range.end; i++) {
            if (lines.get(i).trim().equals(taskLine.trim())) {
                return content;
            }
        }

        int insertAt = range.end;
        while (insertAt > range.contentStart && lines.get(insertAt - 1).isBlank()) {
            insertAt--;
        }
        lines.add(insertAt, taskLine);
        return joinLines(lines);
    }

    public String appendTableRowUnique(String markdown,
                                       String sectionHeading,
                                       List<String> row,
                                       List<Integer> uniqueColumns) {
        String content = normalize(markdown);
        List<String> lines = toLines(content);
        SectionRange range = locateSection(lines, sectionHeading);
        if (range == null) {
            return content;
        }
        int header = -1;
        int separator = -1;
        for (int i = range.contentStart; i < range.end; i++) {
            if (isTableLine(lines.get(i))) {
                header = i;
                if (i + 1 < range.end && isTableLine(lines.get(i + 1))) {
                    separator = i + 1;
                }
                break;
            }
        }
        if (header < 0 || separator < 0) {
            return content;
        }

        int rowStart = separator + 1;
        int rowEnd = rowStart;
        while (rowEnd < range.end && isTableLine(lines.get(rowEnd))) {
            List<String> cells = splitTableRow(lines.get(rowEnd));
            if (isSameOnUniqueColumns(cells, row, uniqueColumns)) {
                return content;
            }
            rowEnd++;
        }

        lines.add(rowEnd, toTableRow(row));
        return joinLines(lines);
    }

    public String normalize(String markdown) {
        return markdown == null ? "" : markdown.replace("\r\n", "\n");
    }

    public Map<String, String> parseSimpleFrontmatter(String markdown) {
        String content = normalize(markdown);
        List<String> lines = toLines(content);
        Map<String, String> result = new LinkedHashMap<>();
        if (lines.isEmpty() || !"---".equals(lines.get(0).trim())) {
            return result;
        }
        int end = findFrontmatterEnd(lines);
        if (end <= 0) {
            return result;
        }
        for (int i = 1; i < end; i++) {
            String line = lines.get(i);
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            result.put(key, trimFrontmatterValue(val));
        }
        return result;
    }

    private String trimFrontmatterValue(String val) {
        String result = val;
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1);
        }
        if (result.startsWith("'") && result.endsWith("'") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private int findFrontmatterEnd(List<String> lines) {
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private String formatFrontmatterValue(String value) {
        if (value == null) {
            return "\"\"";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "\"\"";
        }
        if (trimmed.matches("^[A-Za-z0-9_\\-./]+$")) {
            return trimmed;
        }
        return "\"" + trimmed.replace("\"", "\\\\\"") + "\"";
    }

    private List<String> normalizeBody(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(normalize(body).split("\\n", -1)));
        return trimTrailingBlankLines(trimLeadingBlankLines(lines), 0);
    }

    private List<String> trimLeadingBlankLines(List<String> lines) {
        int start = 0;
        while (start < lines.size() && lines.get(start).isBlank()) {
            start++;
        }
        return new ArrayList<>(lines.subList(start, lines.size()));
    }

    private List<String> trimTrailingBlankLines(List<String> lines, int minKeep) {
        List<String> result = new ArrayList<>(lines);
        int keep = minKeep;
        while (result.size() > keep && result.get(result.size() - 1).isBlank()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private List<String> toLines(String markdown) {
        return new ArrayList<>(Arrays.asList(markdown.split("\\n", -1)));
    }

    private String joinLines(List<String> lines) {
        return String.join("\n", lines);
    }

    private SectionRange locateSection(List<String> lines, String heading) {
        if (heading == null || heading.isBlank()) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).trim().equals(heading.trim())) {
                continue;
            }
            Matcher matcher = HEADING_PATTERN.matcher(lines.get(i).trim());
            if (!matcher.matches()) {
                return null;
            }
            int level = matcher.group(1).length();
            int end = lines.size();
            for (int j = i + 1; j < lines.size(); j++) {
                Matcher next = HEADING_PATTERN.matcher(lines.get(j).trim());
                if (!next.matches()) {
                    continue;
                }
                int nextLevel = next.group(1).length();
                if (nextLevel <= level) {
                    end = j;
                    break;
                }
            }
            return new SectionRange(i, i + 1, end);
        }
        return null;
    }

    private boolean isTableLine(String line) {
        return TABLE_LINE_PATTERN.matcher(line.trim()).matches();
    }

    private List<String> splitTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private boolean isSameOnUniqueColumns(List<String> existing,
                                          List<String> candidate,
                                          List<Integer> uniqueColumns) {
        if (uniqueColumns == null || uniqueColumns.isEmpty()) {
            return Objects.equals(existing, candidate);
        }
        for (Integer index : uniqueColumns) {
            if (index == null) {
                continue;
            }
            String l = index < existing.size() ? existing.get(index) : "";
            String r = index < candidate.size() ? candidate.get(index) : "";
            if (!Objects.equals(l, r)) {
                return false;
            }
        }
        return true;
    }

    private String toTableRow(List<String> row) {
        StringBuilder builder = new StringBuilder("|");
        for (String cell : row) {
            builder.append(' ').append(cell == null ? "" : cell).append(' ').append('|');
        }
        return builder.toString();
    }

    private record SectionRange(int headingIndex, int contentStart, int end) {
    }
}
