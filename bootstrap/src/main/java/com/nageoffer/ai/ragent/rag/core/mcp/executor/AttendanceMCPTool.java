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

package com.nageoffer.ai.ragent.rag.core.mcp.executor;

import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.MCPExecute;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.MCPParam;
import com.nageoffer.ai.ragent.rag.core.mcp.annotation.MCPToolDeclare;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 考勤查询工具（注解式示例）
 * <p>
 * 演示通过 {@link MCPToolDeclare} 注解声明 MCP 工具，
 * 无需实现 MCPToolExecutor 接口。
 */
@Slf4j
@Component
@MCPToolDeclare(
        toolId = "attendance_query",
        name = "考勤查询",
        description = "查询员工考勤记录，包括出勤天数、迟到次数、请假天数等",
        examples = {"查一下我的考勤", "本月考勤记录", "我这个月迟到了几次"},
        requireUserId = true,
        parameters = {
                @MCPParam(name = "month", description = "查询月份，格式 yyyy-MM，默认当月",
                        type = "string", required = false),
                @MCPParam(name = "queryType", description = "查询类型：summary(汇总)/detail(明细)",
                        type = "string", required = false, defaultValue = "summary",
                        enumValues = {"summary", "detail"})
        }
)
public class AttendanceMCPTool {

    @MCPExecute
    public MCPResponse handleQuery(MCPRequest request) {
        String userId = request.getUserId();
        String month = request.getStringParameter("month");
        String queryType = request.getStringParameter("queryType");

        if (month == null || month.isBlank()) {
            month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        if (queryType == null || queryType.isBlank()) {
            queryType = "summary";
        }

        log.info("考勤查询: userId={}, month={}, queryType={}", userId, month, queryType);

        // 模拟考勤数据
        int workDays = 22;
        int attendDays = 20;
        int lateTimes = 2;
        int leaveDays = 2;

        String textResult;
        if ("detail".equals(queryType)) {
            textResult = String.format(
                    "%s 考勤明细：\n- 应出勤 %d 天\n- 实际出勤 %d 天\n- 迟到 %d 次\n- 请假 %d 天\n- 出勤率 %.1f%%",
                    month, workDays, attendDays, lateTimes, leaveDays,
                    (double) attendDays / workDays * 100);
        } else {
            textResult = String.format(
                    "%s 考勤汇总：出勤 %d/%d 天，迟到 %d 次，请假 %d 天",
                    month, attendDays, workDays, lateTimes, leaveDays);
        }

        return MCPResponse.success("attendance_query", textResult, Map.of(
                "month", month,
                "workDays", workDays,
                "attendDays", attendDays,
                "lateTimes", lateTimes,
                "leaveDays", leaveDays
        ));
    }
}
