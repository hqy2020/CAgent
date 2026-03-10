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

package com.openingcloud.ai.ragent.rag.core.hotspot;

import cn.hutool.core.util.StrUtil;
import com.openingcloud.ai.ragent.rag.config.HotspotProperties;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorDO;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorEventDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotspotNotificationService {

    private final HotspotWebSocketHandler hotspotWebSocketHandler;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final HotspotProperties hotspotProperties;

    public NotificationResult notifyMonitorEvents(HotspotMonitorDO monitor, List<HotspotMonitorEventDO> events) {
        if (events == null || events.isEmpty()) {
            return new NotificationResult(false, false);
        }
        boolean websocketDelivered = false;
        boolean emailDelivered = false;
        if (Boolean.TRUE.equals(toBoolean(monitor.getWebsocketEnabled())) && hotspotProperties.isWebsocketEnabled()) {
            websocketDelivered = hotspotWebSocketHandler.sendToUser(
                    String.valueOf(monitor.getUserId()),
                    Map.of(
                            "type", "hotspot-alert",
                            "timestamp", System.currentTimeMillis(),
                            "monitorId", String.valueOf(monitor.getId()),
                            "keyword", monitor.getKeyword(),
                            "events", events
                    )
            );
        }
        if (Boolean.TRUE.equals(toBoolean(monitor.getEmailEnabled())) && StrUtil.isNotBlank(monitor.getEmail())) {
            emailDelivered = sendEmail(monitor, events);
        }
        return new NotificationResult(websocketDelivered, emailDelivered);
    }

    private boolean sendEmail(HotspotMonitorDO monitor, List<HotspotMonitorEventDO> events) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(monitor.getEmail());
            message.setSubject("热点监控告警 | " + monitor.getKeyword() + " | " + events.size() + " 条新结果");
            StringBuilder body = new StringBuilder()
                    .append("监控关键词：").append(monitor.getKeyword()).append('\n')
                    .append("新增热点：").append(events.size()).append(" 条\n\n");
            int index = 1;
            for (HotspotMonitorEventDO event : events) {
                body.append(index++)
                        .append(". [").append(event.getSourceLabel()).append("] ")
                        .append(event.getTitle()).append('\n')
                        .append("判定：").append(StrUtil.blankToDefault(event.getVerdict(), "待核验"))
                        .append(" | 相关性：").append(event.getRelevanceScore())
                        .append(" | 可信度：").append(event.getCredibilityScore()).append('\n')
                        .append("链接：").append(event.getUrl()).append('\n');
                if (StrUtil.isNotBlank(event.getAnalysisSummary())) {
                    body.append("摘要：").append(event.getAnalysisSummary()).append('\n');
                }
                body.append('\n');
            }
            message.setText(body.toString());
            sender.send(message);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to send hotspot email: monitorId={}, email={}", monitor.getId(), monitor.getEmail(), ex);
            return false;
        }
    }

    private Boolean toBoolean(Integer value) {
        return value != null && value == 1;
    }

    public record NotificationResult(boolean websocketDelivered, boolean emailDelivered) {
    }
}
