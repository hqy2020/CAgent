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

package com.openingcloud.ai.ragent.admin.service;

import com.openingcloud.ai.ragent.rag.config.HotspotProperties;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class HotspotMonitorScheduleJob {

    private final HotspotMonitorService hotspotMonitorService;
    private final HotspotProperties hotspotProperties;

    private final String instanceId = resolveInstanceId();

    @Scheduled(fixedDelayString = "${rag.hotspot.scan-delay-ms:10000}")
    public void scan() {
        if (!hotspotProperties.isMonitorEnabled()) {
            return;
        }
        Date now = new Date();
        List<HotspotMonitorDO> dueMonitors = hotspotMonitorService.listDueMonitors(now, hotspotProperties.getScanBatchSize());
        if (dueMonitors.isEmpty()) {
            return;
        }
        Date lockUntil = new Date(System.currentTimeMillis() + Math.max(hotspotProperties.getLockSeconds(), 60L) * 1000L);
        for (HotspotMonitorDO monitor : dueMonitors) {
            if (!hotspotMonitorService.tryAcquireLock(monitor.getId(), now, lockUntil, instanceId)) {
                continue;
            }
            try {
                hotspotMonitorService.executeMonitor(monitor, false);
            } finally {
                hotspotMonitorService.releaseLock(monitor.getId());
            }
        }
    }

    private String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception ex) {
            return "hotspot-monitor-" + UUID.randomUUID();
        }
    }
}
