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

package com.openingcloud.ai.ragent.admin.controller;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.openingcloud.ai.ragent.admin.controller.request.HotspotMonitorSaveRequest;
import com.openingcloud.ai.ragent.admin.controller.vo.HotspotMonitorEventVO;
import com.openingcloud.ai.ragent.admin.controller.vo.HotspotMonitorRunVO;
import com.openingcloud.ai.ragent.admin.controller.vo.HotspotMonitorVO;
import com.openingcloud.ai.ragent.admin.service.HotspotMonitorService;
import com.openingcloud.ai.ragent.framework.convention.Result;
import com.openingcloud.ai.ragent.framework.web.Results;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/hotspots")
public class HotspotController {

    private final HotspotMonitorService hotspotMonitorService;

    @GetMapping("/report")
    public Result<HotspotReport> report(@RequestParam(required = false) String query,
                                        @RequestParam(required = false) String sources,
                                        @RequestParam(required = false) Integer limit,
                                        @RequestParam(defaultValue = "true") boolean analyze) {
        return Results.success(hotspotMonitorService.previewReport(query, sources, limit, analyze));
    }

    @GetMapping("/monitors")
    public Result<IPage<HotspotMonitorVO>> pageMonitors(@RequestParam(defaultValue = "1") long pageNo,
                                                        @RequestParam(defaultValue = "20") long pageSize) {
        return Results.success(hotspotMonitorService.pageMonitors(pageNo, pageSize));
    }

    @PostMapping("/monitors")
    public Result<String> createMonitor(@Valid @RequestBody HotspotMonitorSaveRequest request) {
        return Results.success(hotspotMonitorService.createMonitor(request));
    }

    @PutMapping("/monitors/{id}")
    public Result<Void> updateMonitor(@PathVariable String id, @Valid @RequestBody HotspotMonitorSaveRequest request) {
        hotspotMonitorService.updateMonitor(id, request);
        return Results.success();
    }

    @PostMapping("/monitors/{id}/toggle")
    public Result<Void> toggleMonitor(@PathVariable String id, @RequestParam boolean enabled) {
        hotspotMonitorService.toggleMonitor(id, enabled);
        return Results.success();
    }

    @PostMapping("/monitors/{id}/scan")
    public Result<HotspotMonitorRunVO> scanMonitor(@PathVariable String id) {
        return Results.success(hotspotMonitorService.scanNow(id));
    }

    @GetMapping("/events")
    public Result<IPage<HotspotMonitorEventVO>> pageEvents(@RequestParam(required = false) Long monitorId,
                                                           @RequestParam(defaultValue = "1") long pageNo,
                                                           @RequestParam(defaultValue = "20") long pageSize) {
        return Results.success(hotspotMonitorService.pageEvents(monitorId, pageNo, pageSize));
    }

    @GetMapping("/runs")
    public Result<IPage<HotspotMonitorRunVO>> pageRuns(@RequestParam(required = false) Long monitorId,
                                                       @RequestParam(defaultValue = "1") long pageNo,
                                                       @RequestParam(defaultValue = "10") long pageSize) {
        return Results.success(hotspotMonitorService.pageRuns(monitorId, pageNo, pageSize));
    }
}
