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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openingcloud.ai.ragent.admin.controller.request.HotspotMonitorSaveRequest;
import com.openingcloud.ai.ragent.admin.controller.vo.HotspotMonitorEventVO;
import com.openingcloud.ai.ragent.admin.controller.vo.HotspotMonitorRunVO;
import com.openingcloud.ai.ragent.admin.controller.vo.HotspotMonitorVO;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.rag.config.HotspotProperties;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAggregationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotAnalysisService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotNotificationService;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotReport;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSearchItem;
import com.openingcloud.ai.ragent.rag.core.hotspot.HotspotSource;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorDO;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorEventDO;
import com.openingcloud.ai.ragent.rag.dao.entity.HotspotMonitorRunDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.HotspotMonitorEventMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.HotspotMonitorMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.HotspotMonitorRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotspotMonitorService {

    private final HotspotMonitorMapper hotspotMonitorMapper;
    private final HotspotMonitorEventMapper hotspotMonitorEventMapper;
    private final HotspotMonitorRunMapper hotspotMonitorRunMapper;
    private final HotspotAggregationService hotspotAggregationService;
    private final HotspotAnalysisService hotspotAnalysisService;
    private final HotspotNotificationService hotspotNotificationService;
    private final HotspotProperties hotspotProperties;

    public HotspotReport previewReport(String query, String rawSources, Integer limit, boolean analyze) {
        List<HotspotSource> sources = HotspotSource.parseCsv(rawSources);
        String normalizedQuery = StrUtil.blankToDefault(query, "AI").trim();
        List<String> expandedKeywords = hotspotAnalysisService.expandKeyword(normalizedQuery);
        HotspotReport report = hotspotAggregationService.search(
                normalizedQuery,
                expandedKeywords,
                sources,
                limit == null ? hotspotProperties.getMonitorResultLimit() : limit
        );
        return analyze ? hotspotAnalysisService.analyzeReport(normalizedQuery, report) : report;
    }

    public IPage<HotspotMonitorVO> pageMonitors(long pageNo, long pageSize) {
        Long userId = currentUserId();
        Page<HotspotMonitorDO> page = new Page<>(pageNo, pageSize);
        IPage<HotspotMonitorDO> result = hotspotMonitorMapper.selectPage(page,
                new LambdaQueryWrapper<HotspotMonitorDO>()
                        .eq(HotspotMonitorDO::getUserId, userId)
                        .orderByDesc(HotspotMonitorDO::getUpdateTime));
        Page<HotspotMonitorVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toMonitorVO).toList());
        return voPage;
    }

    public IPage<HotspotMonitorEventVO> pageEvents(Long monitorId, long pageNo, long pageSize) {
        Long userId = currentUserId();
        Page<HotspotMonitorEventDO> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<HotspotMonitorEventDO> wrapper = new LambdaQueryWrapper<HotspotMonitorEventDO>()
                .eq(HotspotMonitorEventDO::getUserId, userId)
                .orderByDesc(HotspotMonitorEventDO::getCreateTime);
        if (monitorId != null) {
            wrapper.eq(HotspotMonitorEventDO::getMonitorId, monitorId);
        }
        IPage<HotspotMonitorEventDO> result = hotspotMonitorEventMapper.selectPage(page, wrapper);
        Page<HotspotMonitorEventVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toEventVO).toList());
        return voPage;
    }

    public IPage<HotspotMonitorRunVO> pageRuns(Long monitorId, long pageNo, long pageSize) {
        Long userId = currentUserId();
        Page<HotspotMonitorRunDO> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<HotspotMonitorRunDO> wrapper = new LambdaQueryWrapper<HotspotMonitorRunDO>()
                .eq(HotspotMonitorRunDO::getUserId, userId)
                .orderByDesc(HotspotMonitorRunDO::getStartedAt);
        if (monitorId != null) {
            wrapper.eq(HotspotMonitorRunDO::getMonitorId, monitorId);
        }
        IPage<HotspotMonitorRunDO> result = hotspotMonitorRunMapper.selectPage(page, wrapper);
        Page<HotspotMonitorRunVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toRunVO).toList());
        return voPage;
    }

    public String createMonitor(HotspotMonitorSaveRequest request) {
        Long userId = currentUserId();
        HotspotMonitorDO monitor = HotspotMonitorDO.builder()
                .userId(userId)
                .keyword(normalizeKeyword(request.getKeyword()))
                .normalizedKeyword(normalizeKeyword(request.getKeyword()).toLowerCase(Locale.ROOT))
                .sources(normalizeSources(request.getSources()))
                .enabled(boolToInt(request.getEnabled(), true))
                .email(StrUtil.trimToNull(request.getEmail()))
                .emailEnabled(boolToInt(request.getEmailEnabled(), false))
                .websocketEnabled(boolToInt(request.getWebsocketEnabled(), true))
                .scanIntervalMinutes(Objects.requireNonNullElse(request.getScanIntervalMinutes(), 30))
                .relevanceThreshold(Objects.requireNonNullElse(request.getRelevanceThreshold(), BigDecimal.valueOf(0.55D)))
                .credibilityThreshold(Objects.requireNonNullElse(request.getCredibilityThreshold(), BigDecimal.valueOf(0.45D)))
                .lastResultCount(0)
                .nextRunTime(nextRunTime(Objects.requireNonNullElse(request.getScanIntervalMinutes(), 30)))
                .build();
        hotspotMonitorMapper.insert(monitor);
        return String.valueOf(monitor.getId());
    }

    public void updateMonitor(String id, HotspotMonitorSaveRequest request) {
        HotspotMonitorDO existing = loadOwnedMonitor(parseId(id));
        existing.setKeyword(normalizeKeyword(request.getKeyword()));
        existing.setNormalizedKeyword(existing.getKeyword().toLowerCase(Locale.ROOT));
        existing.setSources(normalizeSources(request.getSources()));
        existing.setEnabled(boolToInt(request.getEnabled(), existing.getEnabled() != null && existing.getEnabled() == 1));
        existing.setEmail(StrUtil.trimToNull(request.getEmail()));
        existing.setEmailEnabled(boolToInt(request.getEmailEnabled(), existing.getEmailEnabled() != null && existing.getEmailEnabled() == 1));
        existing.setWebsocketEnabled(boolToInt(request.getWebsocketEnabled(), existing.getWebsocketEnabled() != null && existing.getWebsocketEnabled() == 1));
        existing.setScanIntervalMinutes(Objects.requireNonNullElse(request.getScanIntervalMinutes(), existing.getScanIntervalMinutes()));
        existing.setRelevanceThreshold(Objects.requireNonNullElse(request.getRelevanceThreshold(), existing.getRelevanceThreshold()));
        existing.setCredibilityThreshold(Objects.requireNonNullElse(request.getCredibilityThreshold(), existing.getCredibilityThreshold()));
        if (existing.getEnabled() != null && existing.getEnabled() == 1 && existing.getNextRunTime() == null) {
            existing.setNextRunTime(nextRunTime(existing.getScanIntervalMinutes()));
        }
        hotspotMonitorMapper.updateById(existing);
    }

    public void toggleMonitor(String id, boolean enabled) {
        HotspotMonitorDO monitor = loadOwnedMonitor(parseId(id));
        monitor.setEnabled(enabled ? 1 : 0);
        if (enabled) {
            monitor.setNextRunTime(new Date());
            monitor.setLastError(null);
        }
        hotspotMonitorMapper.updateById(monitor);
    }

    public HotspotMonitorRunVO scanNow(String id) {
        HotspotMonitorDO monitor = loadOwnedMonitor(parseId(id));
        return toRunVO(executeMonitor(monitor, true));
    }

    public List<HotspotMonitorDO> listDueMonitors(Date now, int limit) {
        return hotspotMonitorMapper.selectList(
                new LambdaQueryWrapper<HotspotMonitorDO>()
                        .eq(HotspotMonitorDO::getEnabled, 1)
                        .and(wrapper -> wrapper.isNull(HotspotMonitorDO::getNextRunTime)
                                .or()
                                .le(HotspotMonitorDO::getNextRunTime, now))
                        .and(wrapper -> wrapper.isNull(HotspotMonitorDO::getLockUntil)
                                .or()
                                .lt(HotspotMonitorDO::getLockUntil, now))
                        .orderByAsc(HotspotMonitorDO::getNextRunTime)
                        .last("LIMIT " + Math.max(limit, 1))
        );
    }

    public boolean tryAcquireLock(Long monitorId, Date now, Date lockUntil, String owner) {
        return hotspotMonitorMapper.update(null,
                new LambdaUpdateWrapper<HotspotMonitorDO>()
                        .eq(HotspotMonitorDO::getId, monitorId)
                        .and(wrapper -> wrapper.isNull(HotspotMonitorDO::getLockUntil)
                                .or()
                                .lt(HotspotMonitorDO::getLockUntil, now))
                        .set(HotspotMonitorDO::getLockOwner, owner)
                        .set(HotspotMonitorDO::getLockUntil, lockUntil)) > 0;
    }

    public void releaseLock(Long monitorId) {
        hotspotMonitorMapper.update(null,
                new LambdaUpdateWrapper<HotspotMonitorDO>()
                        .eq(HotspotMonitorDO::getId, monitorId)
                        .set(HotspotMonitorDO::getLockOwner, null)
                        .set(HotspotMonitorDO::getLockUntil, null));
    }

    public HotspotMonitorRunDO executeMonitor(HotspotMonitorDO monitor, boolean manual) {
        Date start = new Date();
        HotspotMonitorRunDO run = HotspotMonitorRunDO.builder()
                .monitorId(monitor.getId())
                .userId(monitor.getUserId())
                .keyword(monitor.getKeyword())
                .status("RUNNING")
                .sources(monitor.getSources())
                .startedAt(start)
                .fetchedCount(0)
                .qualifiedCount(0)
                .newEventCount(0)
                .build();
        hotspotMonitorRunMapper.insert(run);

        try {
            List<String> expandedKeywords = hotspotAnalysisService.expandKeyword(monitor.getKeyword());
            HotspotReport report = hotspotAggregationService.search(
                    monitor.getKeyword(),
                    expandedKeywords,
                    HotspotSource.parseCsv(monitor.getSources()),
                    hotspotProperties.getMonitorResultLimit()
            );
            HotspotReport analyzedReport = hotspotAnalysisService.analyzeReport(monitor.getKeyword(), report);
            List<HotspotSearchItem> qualifiedItems = filterQualifiedItems(analyzedReport.getItems(), monitor);
            List<HotspotMonitorEventDO> newEvents = persistNewEvents(monitor, qualifiedItems);
            HotspotNotificationService.NotificationResult notifyResult =
                    hotspotNotificationService.notifyMonitorEvents(monitor, newEvents);
            markNotifications(newEvents, notifyResult);

            monitor.setLastScanTime(start);
            monitor.setLastSuccessTime(new Date());
            monitor.setLastResultCount(qualifiedItems.size());
            monitor.setLastError(null);
            monitor.setNextRunTime(manual ? nextRunTime(monitor.getScanIntervalMinutes()) : nextRunTime(monitor.getScanIntervalMinutes()));
            hotspotMonitorMapper.updateById(monitor);

            run.setStatus("SUCCESS");
            run.setWarning(joinWarnings(report.getWarnings()));
            run.setExpandedQueries(String.join(",", expandedKeywords));
            run.setFetchedCount(analyzedReport.getItems().size());
            run.setQualifiedCount(qualifiedItems.size());
            run.setNewEventCount(newEvents.size());
            run.setFinishedAt(new Date());
            hotspotMonitorRunMapper.updateById(run);
            return run;
        } catch (Exception ex) {
            log.warn("Hotspot monitor execution failed: monitorId={}", monitor.getId(), ex);
            String errorMessage = simplifyError(ex);
            monitor.setLastScanTime(start);
            monitor.setLastError(errorMessage);
            monitor.setNextRunTime(nextRunTime(monitor.getScanIntervalMinutes()));
            hotspotMonitorMapper.updateById(monitor);

            run.setStatus("FAILED");
            run.setWarning(errorMessage);
            run.setFinishedAt(new Date());
            hotspotMonitorRunMapper.updateById(run);
            return run;
        }
    }

    private List<HotspotSearchItem> filterQualifiedItems(List<HotspotSearchItem> items, HotspotMonitorDO monitor) {
        double relevanceThreshold = scaleThreshold(monitor.getRelevanceThreshold(), 0.55D);
        double credibilityThreshold = scaleThreshold(monitor.getCredibilityThreshold(), 0.45D);
        return items.stream()
                .filter(item -> item.getRelevanceScore() == null || item.getRelevanceScore() >= relevanceThreshold)
                .filter(item -> item.getCredibilityScore() == null || item.getCredibilityScore() >= credibilityThreshold)
                .toList();
    }

    private List<HotspotMonitorEventDO> persistNewEvents(HotspotMonitorDO monitor, List<HotspotSearchItem> items) {
        List<HotspotMonitorEventDO> inserted = new ArrayList<>();
        for (HotspotSearchItem item : items) {
            String fingerprint = buildFingerprint(monitor.getKeyword(), item);
            Long exists = hotspotMonitorEventMapper.selectCount(
                    new LambdaQueryWrapper<HotspotMonitorEventDO>()
                            .eq(HotspotMonitorEventDO::getMonitorId, monitor.getId())
                            .eq(HotspotMonitorEventDO::getFingerprint, fingerprint)
            );
            if (exists != null && exists > 0) {
                continue;
            }
            HotspotMonitorEventDO event = HotspotMonitorEventDO.builder()
                    .monitorId(monitor.getId())
                    .userId(monitor.getUserId())
                    .keyword(monitor.getKeyword())
                    .fingerprint(fingerprint)
                    .title(item.getTitle())
                    .summary(item.getSummary())
                    .url(item.getUrl())
                    .source(item.getSource())
                    .sourceLabel(item.getSourceLabel())
                    .publishedAt(item.getPublishedAt() == null ? null : Date.from(item.getPublishedAt()))
                    .hotScore(scaleNumber(item.getHotScore()))
                    .relevanceScore(scaleNumber(item.getRelevanceScore()))
                    .credibilityScore(scaleNumber(item.getCredibilityScore()))
                    .verdict(item.getVerdict())
                    .analysisSummary(item.getAnalysisSummary())
                    .analysisReason(item.getAnalysisReason())
                    .authorName(item.getAuthorName())
                    .notifiedWebsocket(0)
                    .notifiedEmail(0)
                    .build();
            hotspotMonitorEventMapper.insert(event);
            inserted.add(event);
        }
        return inserted;
    }

    private void markNotifications(List<HotspotMonitorEventDO> newEvents,
                                   HotspotNotificationService.NotificationResult notifyResult) {
        if (newEvents.isEmpty()) {
            return;
        }
        for (HotspotMonitorEventDO event : newEvents) {
            boolean changed = false;
            if (notifyResult.websocketDelivered()) {
                event.setNotifiedWebsocket(1);
                changed = true;
            }
            if (notifyResult.emailDelivered()) {
                event.setNotifiedEmail(1);
                changed = true;
            }
            if (changed) {
                hotspotMonitorEventMapper.updateById(event);
            }
        }
    }

    private HotspotMonitorDO loadOwnedMonitor(Long monitorId) {
        HotspotMonitorDO monitor = hotspotMonitorMapper.selectById(monitorId);
        if (monitor == null) {
            throw new ClientException("监控任务不存在");
        }
        Long userId = currentUserId();
        if (!Objects.equals(userId, monitor.getUserId())) {
            throw new ClientException("无权限访问该监控任务");
        }
        return monitor;
    }

    private Long currentUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(userId)) {
            throw new ClientException("未获取到当前用户");
        }
        return Long.parseLong(userId);
    }

    private Long parseId(String id) {
        if (!StrUtil.isNumeric(id)) {
            throw new ClientException("任务 ID 非法");
        }
        return Long.parseLong(id);
    }

    private String normalizeKeyword(String keyword) {
        String normalized = StrUtil.trimToNull(keyword);
        if (normalized == null) {
            throw new ClientException("监控关键词不能为空");
        }
        return normalized;
    }

    private String normalizeSources(List<String> sources) {
        List<HotspotSource> parsed = sources == null || sources.isEmpty()
                ? HotspotSource.parseList(hotspotProperties.getDefaultSources())
                : HotspotSource.parseList(sources);
        if (parsed.isEmpty()) {
            throw new ClientException("至少选择一个数据源");
        }
        return String.join(",", parsed.stream().map(HotspotSource::getCode).toList());
    }

    private Integer boolToInt(Boolean value, boolean defaultValue) {
        return Boolean.TRUE.equals(value != null ? value : Boolean.valueOf(defaultValue)) ? 1 : 0;
    }

    private Date nextRunTime(Integer intervalMinutes) {
        int minutes = Math.max(5, intervalMinutes == null ? 30 : intervalMinutes);
        return new Date(System.currentTimeMillis() + minutes * 60_000L);
    }

    private double scaleThreshold(BigDecimal value, double defaultValue) {
        return value == null ? defaultValue : value.doubleValue();
    }

    private BigDecimal scaleNumber(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String buildFingerprint(String keyword, HotspotSearchItem item) {
        try {
            String raw = normalizeKeyword(keyword).toLowerCase(Locale.ROOT)
                    + "|"
                    + StrUtil.blankToDefault(item.getUrl(), "")
                    + "|"
                    + StrUtil.blankToDefault(item.getTitle(), "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return UUID.randomUUID().toString();
        }
    }

    private String joinWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        String joined = String.join("；", warnings);
        return joined.length() > 500 ? joined.substring(0, 500) : joined;
    }

    private String simplifyError(Exception ex) {
        String message = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private HotspotMonitorVO toMonitorVO(HotspotMonitorDO monitor) {
        return HotspotMonitorVO.builder()
                .id(String.valueOf(monitor.getId()))
                .keyword(monitor.getKeyword())
                .sources(HotspotSource.parseCsv(monitor.getSources()).stream().map(HotspotSource::getCode).toList())
                .enabled(monitor.getEnabled() != null && monitor.getEnabled() == 1)
                .email(monitor.getEmail())
                .emailEnabled(monitor.getEmailEnabled() != null && monitor.getEmailEnabled() == 1)
                .websocketEnabled(monitor.getWebsocketEnabled() != null && monitor.getWebsocketEnabled() == 1)
                .scanIntervalMinutes(monitor.getScanIntervalMinutes())
                .relevanceThreshold(monitor.getRelevanceThreshold())
                .credibilityThreshold(monitor.getCredibilityThreshold())
                .lastScanTime(monitor.getLastScanTime())
                .lastSuccessTime(monitor.getLastSuccessTime())
                .lastError(monitor.getLastError())
                .lastResultCount(monitor.getLastResultCount())
                .nextRunTime(monitor.getNextRunTime())
                .createTime(monitor.getCreateTime())
                .updateTime(monitor.getUpdateTime())
                .build();
    }

    private HotspotMonitorEventVO toEventVO(HotspotMonitorEventDO event) {
        return HotspotMonitorEventVO.builder()
                .id(String.valueOf(event.getId()))
                .monitorId(String.valueOf(event.getMonitorId()))
                .keyword(event.getKeyword())
                .title(event.getTitle())
                .summary(event.getSummary())
                .url(event.getUrl())
                .source(event.getSource())
                .sourceLabel(event.getSourceLabel())
                .publishedAt(event.getPublishedAt())
                .hotScore(event.getHotScore())
                .relevanceScore(event.getRelevanceScore())
                .credibilityScore(event.getCredibilityScore())
                .verdict(event.getVerdict())
                .analysisSummary(event.getAnalysisSummary())
                .analysisReason(event.getAnalysisReason())
                .authorName(event.getAuthorName())
                .notifiedWebsocket(event.getNotifiedWebsocket() != null && event.getNotifiedWebsocket() == 1)
                .notifiedEmail(event.getNotifiedEmail() != null && event.getNotifiedEmail() == 1)
                .createTime(event.getCreateTime())
                .build();
    }

    private HotspotMonitorRunVO toRunVO(HotspotMonitorRunDO run) {
        return HotspotMonitorRunVO.builder()
                .id(String.valueOf(run.getId()))
                .monitorId(String.valueOf(run.getMonitorId()))
                .keyword(run.getKeyword())
                .status(run.getStatus())
                .warning(run.getWarning())
                .sources(StrUtil.isBlank(run.getSources()) ? List.of() : List.of(run.getSources().split(",")))
                .expandedQueries(StrUtil.isBlank(run.getExpandedQueries()) ? List.of() : List.of(run.getExpandedQueries().split(",")))
                .fetchedCount(run.getFetchedCount())
                .qualifiedCount(run.getQualifiedCount())
                .newEventCount(run.getNewEventCount())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .build();
    }
}
