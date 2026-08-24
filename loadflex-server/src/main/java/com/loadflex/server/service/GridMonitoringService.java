package com.loadflex.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.GridRiskAlert;
import com.loadflex.common.entity.GridTransformer;
import com.loadflex.common.entity.GridTransformerMetric;
import com.loadflex.common.entity.GridTransformerMetricHistory;
import com.loadflex.common.entity.GridUserTransformerMonitor;
import com.loadflex.common.mapper.GridRiskAlertMapper;
import com.loadflex.common.mapper.GridTransformerMapper;
import com.loadflex.common.mapper.GridTransformerMetricHistoryMapper;
import com.loadflex.common.mapper.GridTransformerMetricMapper;
import com.loadflex.common.mapper.GridUserTransformerMonitorMapper;
import com.loadflex.server.api.GridTelemetrySnapshot;
import com.loadflex.server.security.UserContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GridMonitoringService {
    private final GridTransformerMapper transformerMapper;
    private final GridTransformerMetricMapper metricMapper;
    private final GridTransformerMetricHistoryMapper metricHistoryMapper;
    private final GridRiskAlertMapper alertMapper;
    private final GridUserTransformerMonitorMapper monitorMapper;
    private final Map<String, GridTelemetrySnapshot> telemetrySnapshots = new ConcurrentHashMap<>();

    public GridMonitoringService(
            GridTransformerMapper transformerMapper,
            GridTransformerMetricMapper metricMapper,
            GridTransformerMetricHistoryMapper metricHistoryMapper,
            GridRiskAlertMapper alertMapper,
            GridUserTransformerMonitorMapper monitorMapper) {
        this.transformerMapper = transformerMapper;
        this.metricMapper = metricMapper;
        this.metricHistoryMapper = metricHistoryMapper;
        this.alertMapper = alertMapper;
        this.monitorMapper = monitorMapper;
    }

    public List<GridTransformer> transformers() {
        List<String> monitoredCodes = monitoredCodes(UserContext.get().getUserId());
        List<GridTransformer> transformers = transformerMapper.selectList(new LambdaQueryWrapper<GridTransformer>()
                .eq(GridTransformer::getStatus, "ACTIVE")
                .orderByAsc(GridTransformer::getTransformerCode));
        transformers.forEach(transformer ->
                transformer.setMonitoringEnabled(monitoredCodes.contains(transformer.getTransformerCode())));
        return transformers;
    }

    public List<GridTransformer> monitoredTransformers() {
        return transformersByCodes(monitoredCodes(UserContext.get().getUserId()));
    }

    public List<GridTransformer> systemMonitoredTransformers() {
        List<String> codes = monitorMapper
                .selectList(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                        .select(GridUserTransformerMonitor::getTransformerCode)
                        .groupBy(GridUserTransformerMonitor::getTransformerCode))
                .stream()
                .map(GridUserTransformerMonitor::getTransformerCode)
                .collect(Collectors.toList());
        return transformersByCodes(codes);
    }

    @Transactional
    public GridTransformer startMonitoring(String transformerCode) {
        GridTransformer transformer = activeTransformer(transformerCode);
        long userId = UserContext.get().getUserId();
        Long existing = monitorMapper.selectCount(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                .eq(GridUserTransformerMonitor::getUserId, userId)
                .eq(GridUserTransformerMonitor::getTransformerCode, transformerCode));
        if (existing == 0) {
            GridUserTransformerMonitor monitor = new GridUserTransformerMonitor();
            monitor.setUserId(userId);
            monitor.setTransformerCode(transformerCode);
            monitorMapper.insert(monitor);
        }
        if (!Boolean.TRUE.equals(transformer.getMonitoringEnabled())) {
            transformer.setMonitoringEnabled(true);
            transformerMapper.updateById(transformer);
        }
        return transformer;
    }

    @Transactional
    public GridTransformer stopMonitoring(String transformerCode) {
        GridTransformer transformer = activeTransformer(transformerCode);
        long userId = UserContext.get().getUserId();
        monitorMapper.delete(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                .eq(GridUserTransformerMonitor::getUserId, userId)
                .eq(GridUserTransformerMonitor::getTransformerCode, transformerCode));
        alertMapper.delete(new LambdaQueryWrapper<GridRiskAlert>()
                .eq(GridRiskAlert::getUserId, userId)
                .eq(GridRiskAlert::getTransformerCode, transformerCode));
        if (!isMonitored(transformerCode)) {
            transformer.setMonitoringEnabled(false);
            transformerMapper.updateById(transformer);
            metricMapper.deleteById(transformerCode);
            telemetrySnapshots.remove(transformerCode);
        } else {
            transformer.setMonitoringEnabled(true);
        }
        return transformer;
    }

    public boolean isMonitored(String transformerCode) {
        Long count = monitorMapper.selectCount(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                .eq(GridUserTransformerMonitor::getTransformerCode, transformerCode));
        return count > 0;
    }

    public List<Long> monitoringUserIds(String transformerCode) {
        return monitorMapper
                .selectList(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                        .select(GridUserTransformerMonitor::getUserId)
                        .eq(GridUserTransformerMonitor::getTransformerCode, transformerCode))
                .stream()
                .map(GridUserTransformerMonitor::getUserId)
                .collect(Collectors.toList());
    }

    private List<GridTransformer> transformersByCodes(List<String> monitoredCodes) {
        if (monitoredCodes.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return transformerMapper.selectList(new LambdaQueryWrapper<GridTransformer>()
                .eq(GridTransformer::getStatus, "ACTIVE")
                .in(GridTransformer::getTransformerCode, monitoredCodes)
                .orderByAsc(GridTransformer::getTransformerCode));
    }

    private List<String> monitoredCodes(long userId) {
        return monitorMapper
                .selectList(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                        .select(GridUserTransformerMonitor::getTransformerCode)
                        .eq(GridUserTransformerMonitor::getUserId, userId))
                .stream()
                .map(GridUserTransformerMonitor::getTransformerCode)
                .collect(Collectors.toList());
    }

    public List<GridTransformerMetric> latestMetrics() {
        List<String> monitoredCodes = monitoredTransformers().stream()
                .map(GridTransformer::getTransformerCode)
                .collect(Collectors.toList());
        if (monitoredCodes.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return metricMapper.selectList(new LambdaQueryWrapper<GridTransformerMetric>()
                .in(GridTransformerMetric::getTransformerCode, monitoredCodes)
                .orderByAsc(GridTransformerMetric::getTransformerCode));
    }

    public void recordTelemetrySnapshot(
            GridTransformer transformer, long sampleTimeEpochMs, Map<String, BigDecimal> meterLoadsKw) {
        BigDecimal totalLoadKw = meterLoadsKw.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        GridTelemetrySnapshot snapshot = new GridTelemetrySnapshot(
                transformer.getTransformerCode(),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(sampleTimeEpochMs), ZoneId.of("Asia/Shanghai")),
                totalLoadKw,
                transformer.getRatedCapacityKw(),
                new LinkedHashMap<>(meterLoadsKw));
        telemetrySnapshots.put(transformer.getTransformerCode(), snapshot);
    }

    public List<GridTelemetrySnapshot> latestTelemetry() {
        List<GridTelemetrySnapshot> snapshots = new ArrayList<>();
        for (GridTransformer transformer : monitoredTransformers()) {
            GridTelemetrySnapshot snapshot = telemetrySnapshots.get(transformer.getTransformerCode());
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        snapshots.sort(Comparator.comparing(GridTelemetrySnapshot::transformerCode));
        return snapshots;
    }

    public List<GridTransformerMetricHistory> metricHistory(String transformerCode, Integer minutes) {
        requireCurrentUserMonitoring(transformerCode);
        int rangeMinutes = minutes == null ? 60 : Math.max(1, Math.min(minutes, 360));
        int resultLimit = rangeMinutes * 6 + 12;
        LocalDateTime rangeStart = LocalDateTime.now().minusMinutes(rangeMinutes);
        List<GridTransformerMetricHistory> descending =
                metricHistoryMapper.selectList(new LambdaQueryWrapper<GridTransformerMetricHistory>()
                        .eq(GridTransformerMetricHistory::getTransformerCode, transformerCode)
                        .ge(GridTransformerMetricHistory::getWindowEnd, rangeStart)
                        .orderByDesc(GridTransformerMetricHistory::getWindowEnd)
                        .last("LIMIT " + resultLimit));
        java.util.Collections.reverse(descending);
        return descending;
    }

    public List<GridRiskAlert> alerts(String transformerCode, String alertType, Integer limit) {
        int resultLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 50));
        long userId = UserContext.get().getUserId();
        List<String> monitoredCodes = monitoredCodes(userId);
        if (monitoredCodes.isEmpty()
                || (transformerCode != null
                        && !transformerCode.isBlank()
                        && !monitoredCodes.contains(transformerCode))) {
            return java.util.Collections.emptyList();
        }
        LambdaQueryWrapper<GridRiskAlert> query = new LambdaQueryWrapper<GridRiskAlert>()
                .eq(GridRiskAlert::getUserId, userId)
                .in(GridRiskAlert::getTransformerCode, monitoredCodes)
                .eq(
                        transformerCode != null && !transformerCode.isBlank(),
                        GridRiskAlert::getTransformerCode,
                        transformerCode)
                .eq(alertType != null && !alertType.isBlank(), GridRiskAlert::getAlertType, alertType)
                .orderByDesc(GridRiskAlert::getEventTime)
                .last("LIMIT " + resultLimit);
        return alertMapper.selectList(query);
    }

    private void requireCurrentUserMonitoring(String transformerCode) {
        activeTransformer(transformerCode);
        long userId = UserContext.get().getUserId();
        Long count = monitorMapper.selectCount(new LambdaQueryWrapper<GridUserTransformerMonitor>()
                .eq(GridUserTransformerMonitor::getUserId, userId)
                .eq(GridUserTransformerMonitor::getTransformerCode, transformerCode));
        if (count == 0) {
            throw new IllegalArgumentException("当前用户未监控该变压器：" + transformerCode);
        }
    }

    private GridTransformer activeTransformer(String transformerCode) {
        if (transformerCode == null || transformerCode.isBlank()) {
            throw new IllegalArgumentException("请选择要监控的变压器");
        }
        GridTransformer transformer = transformerMapper.selectOne(new LambdaQueryWrapper<GridTransformer>()
                .eq(GridTransformer::getTransformerCode, transformerCode)
                .eq(GridTransformer::getStatus, "ACTIVE"));
        if (transformer == null) {
            throw new IllegalArgumentException("变压器不存在或已停用：" + transformerCode);
        }
        return transformer;
    }
}
