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

package com.nageoffer.ai.ragent.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.admin.controller.vo.MCPToolCallAuditVO;
import com.nageoffer.ai.ragent.admin.service.MCPToolAuditAdminService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/tool-audits")
public class ToolAuditController {

    private final MCPToolAuditAdminService auditAdminService;

    @GetMapping
    public Result<IPage<MCPToolCallAuditVO>> pageAudits(@RequestParam(required = false) String toolId,
                                                        @RequestParam(required = false) Boolean success,
                                                        @RequestParam(required = false) Boolean fallbackUsed,
                                                        @RequestParam(required = false)
                                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                        LocalDateTime startTimeFrom,
                                                        @RequestParam(required = false)
                                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                        LocalDateTime startTimeTo,
                                                        @RequestParam(defaultValue = "1") long pageNo,
                                                        @RequestParam(defaultValue = "20") long pageSize) {
        checkAdmin();
        return Results.success(auditAdminService.pageAudits(
                toolId,
                success,
                fallbackUsed,
                startTimeFrom,
                startTimeTo,
                pageNo,
                pageSize
        ));
    }

    @GetMapping("/{id}")
    public Result<MCPToolCallAuditVO> getAudit(@PathVariable Long id) {
        checkAdmin();
        return Results.success(auditAdminService.getAudit(id));
    }

    private void checkAdmin() {
        StpUtil.checkRole("admin");
    }
}
