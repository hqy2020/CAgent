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

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPTool;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPExecute;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPParam;
import com.openingcloud.ai.ragent.rag.core.mcp.annotation.MCPToolDeclare;
import com.openingcloud.ai.ragent.rag.core.mcp.governance.MCPErrorClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Obsidian MCP 工具 — 更新笔记（追加/前插/日记追加）
 */
@Slf4j
@Component
@MCPToolDeclare(
        toolId = "obsidian_update",
        name = "更新 Obsidian 笔记",
        description = "向已有笔记追加或前插内容，也可向今日日记或指定日期日记追加内容。",
        useWhen = "当用户明确要往已有笔记或日记中追加内容、插入摘要、补充待办或补写记录时使用。",
        avoidWhen = "不要用于新建笔记、全文替换文本、删除笔记或仅仅读取笔记内容。",
        examples = {"在日记里追加一条待办", "往 README 笔记末尾添加内容", "在笔记开头插入摘要"},
        sceneKeywords = {"Obsidian", "笔记更新", "日记写入"},
        requireUserId = true,
        operationType = MCPTool.OperationType.WRITE,
        confirmationRequired = true,
        timeoutSeconds = 15,
        maxRetries = 0,
        sensitivity = MCPTool.Sensitivity.HIGH,
        sensitiveParams = {"content"},
        fallbackMessage = "Obsidian 写入暂时不可用，本次不会执行更新。",
        parameters = {
                @MCPParam(name = "content", description = "要追加或前插的 Markdown 内容", type = "string",
                        required = true, example = "- [ ] 补充 RAG 工具调用设计"),
                @MCPParam(name = "file", description = "目标笔记文件名（不含 .md 后缀）", type = "string",
                        required = false, example = "README"),
                @MCPParam(name = "path", description = "目标笔记相对路径", type = "string", required = false,
                        example = "Projects/Ragent/README.md"),
                @MCPParam(name = "position", description = "插入位置：append 追加到末尾，prepend 插入到开头", type = "string", required = false,
                        defaultValue = "append", example = "append", enumValues = {"append", "prepend"}),
                @MCPParam(name = "daily", description = "是否写入日记", type = "string", required = false,
                        defaultValue = "false", example = "true", enumValues = {"true", "false"}),
                @MCPParam(name = "date", description = "目标日期，仅 daily=true 时生效，格式 YYYY-MM-DD", type = "string",
                        required = false, example = "2026-03-08", pattern = "^\\d{4}-\\d{2}-\\d{2}$")
        }
)
public class ObsidianUpdateNoteTool {

    private final ObsidianCliExecutor cliExecutor;
    private final MCPErrorClassifier errorClassifier;

    @Autowired
    public ObsidianUpdateNoteTool(ObsidianCliExecutor cliExecutor, MCPErrorClassifier errorClassifier) {
        this.cliExecutor = cliExecutor;
        this.errorClassifier = errorClassifier;
    }

    public ObsidianUpdateNoteTool(ObsidianCliExecutor cliExecutor) {
        this(cliExecutor, new MCPErrorClassifier());
    }

    private static final Pattern TODAY_DAILY_PATTERN = Pattern.compile("今日日记|今天(?:的)?日记|今日(?:的)?日记");
    private static final Pattern TARGET_DAILY_ISO_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{4})[-./年](\\d{1,2})[-./月](\\d{1,2})日?\\s*(?:的)?\\s*日记");
    private static final Pattern TARGET_DAILY_MONTH_DAY_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})\\s*(?:的)?\\s*日记");
    private static final Pattern TARGET_DAILY_CN_MONTH_DAY_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日\\s*(?:的)?\\s*日记");
    private static final Pattern SIMPLE_MONTH_DAY_PATTERN = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})$");
    private static final Pattern CN_MONTH_DAY_PATTERN = Pattern.compile("^(\\d{1,2})月(\\d{1,2})日?$");
    private static final Pattern TODO_TAIL_PATTERN =
            Pattern.compile("(?:待办(?:事项)?|todo)[，,:：]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECKBOX_PATTERN =
            Pattern.compile("^\\s*[-*]\\s*\\[[ xX]\\]\\s+.+");

    @MCPExecute
    public MCPResponse handle(MCPRequest request) {
        String content = request.getStringParameter("content");
        if (content == null || content.isBlank()) {
            return MCPResponse.error("obsidian_update", "MISSING_PARAM", "必须提供 content 参数");
        }

        String file = request.getStringParameter("file");
        String path = request.getStringParameter("path");
        String position = request.getStringParameter("position");
        String daily = request.getStringParameter("daily");
        String date = request.getStringParameter("date");
        String userQuestion = request.getUserQuestion();

        boolean isDaily = "true".equalsIgnoreCase(daily);
        boolean isPrepend = "prepend".equalsIgnoreCase(position);
        String normalizedContent = normalizeContent(content, userQuestion);

        String command;
        if (isDaily) {
            command = "daily:append";
        } else if (isPrepend) {
            command = "prepend";
        } else {
            command = "append";
        }

        List<String> args = new ArrayList<>();
        args.add("content=" + normalizedContent);
        if (isDaily) {
            DailyDateDecision decision = resolveDailyDate(userQuestion, date);
            if (decision.hasConflict()) {
                return MCPResponse.error("obsidian_update", "DATE_CONFLICT", decision.conflictMessage());
            }
            if (decision.resolvedDate() != null && !decision.resolvedDate().isBlank()) {
                args.add("date=" + decision.resolvedDate());
            }
        } else {
            if (path != null && !path.isBlank()) {
                args.add("path=" + path);
            } else if (file != null && !file.isBlank()) {
                args.add("file=" + file);
            } else {
                return MCPResponse.error("obsidian_update", "MISSING_PARAM", "非日记模式下必须提供 file 或 path 参数");
            }
        }

        ObsidianCliExecutor.CliResult result = cliExecutor.execute(command, args);
        if (result == null) {
            return MCPResponse.error("obsidian_update", "EXECUTION_ERROR", "Obsidian 更新执行器未返回结果");
        }
        if (!result.isSuccess()) {
            return errorClassifier.classifyCliFailure("obsidian_update", result.stderr());
        }
        MCPResponse response = MCPResponse.success("obsidian_update", "笔记更新成功。\n" + result.stdout());
        response.setFallbackUsed(result.stdout().contains("[fallback]"));
        return response;
    }

    private DailyDateDecision resolveDailyDate(String userQuestion, String extractedDate) {
        LocalDate today = LocalDate.now();
        boolean todayDailyMentioned = containsTodayDailySemantic(userQuestion);
        LocalDate explicitTargetDate = extractTargetDailyDate(userQuestion);
        LocalDate extractedTargetDate = parseDateText(extractedDate);

        if (todayDailyMentioned && explicitTargetDate != null && !explicitTargetDate.equals(today)) {
            String conflict = String.format(
                    "检测到日期冲突：你同时提到了“今日日记”（%s）和“指定日日记”（%s）。请确认要写入哪一天：%s 或 %s。",
                    today,
                    explicitTargetDate,
                    today,
                    explicitTargetDate
            );
            return DailyDateDecision.conflict(conflict);
        }

        if (todayDailyMentioned) {
            return DailyDateDecision.resolved(today.toString());
        }

        if (explicitTargetDate != null) {
            return DailyDateDecision.resolved(explicitTargetDate.toString());
        }

        if (extractedTargetDate != null) {
            return DailyDateDecision.resolved(extractedTargetDate.toString());
        }

        return DailyDateDecision.resolved(null);
    }

    private LocalDate extractTargetDailyDate(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return null;
        }

        LocalDate parsed = null;
        Matcher isoMatcher = TARGET_DAILY_ISO_PATTERN.matcher(userQuestion);
        while (isoMatcher.find()) {
            parsed = buildDate(safeParseInt(isoMatcher.group(1)), safeParseInt(isoMatcher.group(2)), safeParseInt(isoMatcher.group(3)));
        }
        if (parsed != null) {
            return parsed;
        }

        Matcher monthDayMatcher = TARGET_DAILY_MONTH_DAY_PATTERN.matcher(userQuestion);
        while (monthDayMatcher.find()) {
            parsed = buildDate(LocalDate.now().getYear(), safeParseInt(monthDayMatcher.group(1)), safeParseInt(monthDayMatcher.group(2)));
        }
        if (parsed != null) {
            return parsed;
        }

        Matcher cnMonthDayMatcher = TARGET_DAILY_CN_MONTH_DAY_PATTERN.matcher(userQuestion);
        while (cnMonthDayMatcher.find()) {
            parsed = buildDate(LocalDate.now().getYear(), safeParseInt(cnMonthDayMatcher.group(1)), safeParseInt(cnMonthDayMatcher.group(2)));
        }
        if (parsed != null) {
            return parsed;
        }
        return parsed;
    }

    private LocalDate parseDateText(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        String trimmed = rawDate.trim();
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
        } catch (Exception ignore) {
            // fallback to month-day parsing below
        }

        Matcher simpleMonthDay = SIMPLE_MONTH_DAY_PATTERN.matcher(trimmed);
        if (simpleMonthDay.matches()) {
            return buildDate(LocalDate.now().getYear(), safeParseInt(simpleMonthDay.group(1)), safeParseInt(simpleMonthDay.group(2)));
        }

        Matcher cnMonthDay = CN_MONTH_DAY_PATTERN.matcher(trimmed);
        if (cnMonthDay.matches()) {
            return buildDate(LocalDate.now().getYear(), safeParseInt(cnMonthDay.group(1)), safeParseInt(cnMonthDay.group(2)));
        }
        return null;
    }

    private String normalizeContent(String rawContent, String userQuestion) {
        String normalized = rawContent == null ? "" : rawContent.trim();
        boolean todoIntent = containsTodoIntent(userQuestion, normalized);

        if (todoIntent) {
            String tail = extractTodoTail(userQuestion);
            if (tail != null && !tail.isBlank()) {
                normalized = tail.trim();
            }
            if (!isCheckboxContent(normalized)) {
                normalized = "- [ ] " + trimListPrefix(normalized);
            }
        }
        return normalized;
    }

    private String extractTodoTail(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return null;
        }
        Matcher matcher = TODO_TAIL_PATTERN.matcher(userQuestion);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private boolean isCheckboxContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return CHECKBOX_PATTERN.matcher(content).matches();
    }

    private String trimListPrefix(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.replaceFirst("^\\s*[-*]\\s*", "");
        trimmed = trimmed.replaceFirst("^\\[[ xX]\\]\\s*", "");
        return trimmed.strip();
    }

    private boolean containsTodayDailySemantic(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return false;
        }
        return TODAY_DAILY_PATTERN.matcher(userQuestion).find();
    }

    private boolean containsTodoIntent(String userQuestion, String content) {
        String q = userQuestion == null ? "" : userQuestion;
        String c = content == null ? "" : content;
        return q.contains("待办") || q.toLowerCase().contains("todo") || c.contains("待办") || c.toLowerCase().contains("todo");
    }

    private int safeParseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return -1;
        }
    }

    private LocalDate buildDate(int year, int month, int day) {
        if (year <= 0 || month <= 0 || day <= 0) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    private record DailyDateDecision(String resolvedDate, String conflictMessage) {
        static DailyDateDecision resolved(String resolvedDate) {
            return new DailyDateDecision(resolvedDate, null);
        }

        static DailyDateDecision conflict(String conflictMessage) {
            return new DailyDateDecision(null, conflictMessage);
        }

        boolean hasConflict() {
            return conflictMessage != null && !conflictMessage.isBlank();
        }
    }
}
