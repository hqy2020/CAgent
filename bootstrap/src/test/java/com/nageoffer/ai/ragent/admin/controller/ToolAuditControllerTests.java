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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.admin.controller.vo.MCPToolCallAuditVO;
import com.nageoffer.ai.ragent.admin.service.MCPToolAuditAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolAuditControllerTests {

    private MCPToolAuditAdminService auditAdminService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        auditAdminService = mock(MCPToolAuditAdminService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ToolAuditController(auditAdminService)).build();
    }

    @Test
    void shouldPageAuditsWithFilters() throws Exception {
        LocalDateTime startTimeFrom = LocalDateTime.parse("2026-03-08T20:00:00");
        LocalDateTime startTimeTo = LocalDateTime.parse("2026-03-08T21:00:00");
        Page<MCPToolCallAuditVO> page = new Page<>(2, 10, 1);
        page.setRecords(List.of(MCPToolCallAuditVO.builder()
                .id(1L)
                .toolId("web_news_search")
                .success(true)
                .fallbackUsed(false)
                .build()));
        when(auditAdminService.pageAudits(
                eq("web_news_search"),
                eq(true),
                eq(false),
                eq(startTimeFrom),
                eq(startTimeTo),
                eq(2L),
                eq(10L)
        )).thenReturn(page);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            mockMvc.perform(get("/admin/tool-audits")
                            .param("toolId", "web_news_search")
                            .param("success", "true")
                            .param("fallbackUsed", "false")
                            .param("startTimeFrom", "2026-03-08T20:00:00")
                            .param("startTimeTo", "2026-03-08T21:00:00")
                            .param("pageNo", "2")
                            .param("pageSize", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.records[0].toolId").value("web_news_search"))
                    .andExpect(jsonPath("$.data.records[0].success").value(true))
                    .andExpect(jsonPath("$.data.records[0].fallbackUsed").value(false));

            stpUtil.verify(() -> StpUtil.checkRole("admin"));
        }

        verify(auditAdminService).pageAudits(
                "web_news_search",
                true,
                false,
                startTimeFrom,
                startTimeTo,
                2L,
                10L
        );
    }

    @Test
    void shouldGetSingleAudit() throws Exception {
        when(auditAdminService.getAudit(7L)).thenReturn(MCPToolCallAuditVO.builder()
                .id(7L)
                .toolId("obsidian_update")
                .success(false)
                .errorCode("TIMEOUT")
                .build());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            mockMvc.perform(get("/admin/tool-audits/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.id").value(7))
                    .andExpect(jsonPath("$.data.toolId").value("obsidian_update"))
                    .andExpect(jsonPath("$.data.errorCode").value("TIMEOUT"));

            stpUtil.verify(() -> StpUtil.checkRole("admin"));
        }

        verify(auditAdminService).getAudit(7L);
    }
}
