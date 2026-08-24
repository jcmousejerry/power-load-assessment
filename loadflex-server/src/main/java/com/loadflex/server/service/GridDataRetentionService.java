package com.loadflex.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.GridRiskAlert;
import com.loadflex.common.entity.GridTransformerMetricHistory;
import com.loadflex.common.mapper.GridRiskAlertMapper;
import com.loadflex.common.mapper.GridTransformerMetricHistoryMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GridDataRetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GridDataRetentionService.class);

    private final GridTransformerMetricHistoryMapper metricHistoryMapper;
    private final GridRiskAlertMapper alertMapper;
    private final int metricRetentionDays;
    private final int alertRetentionDays;
    private final int alertMaxRecords;

    public GridDataRetentionService(
            GridTransformerMetricHistoryMapper metricHistoryMapper,
            GridRiskAlertMapper alertMapper,
            @Value("${loadflex.grid.metric-retention-days:7}") int metricRetentionDays,
            @Value("${loadflex.grid.alert-retention-days:7}") int alertRetentionDays,
            @Value("${loadflex.grid.alert-max-records:50}") int alertMaxRecords) {
        this.metricHistoryMapper = metricHistoryMapper;
        this.alertMapper = alertMapper;
        this.metricRetentionDays = metricRetentionDays;
        this.alertRetentionDays = alertRetentionDays;
        this.alertMaxRecords = Math.max(1, alertMaxRecords);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        deleteExpiredRealtimeResults();
    }

    @Scheduled(
            initialDelayString = "${loadflex.grid.retention-cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${loadflex.grid.retention-cleanup-interval-ms:3600000}")
    public void deleteExpiredRealtimeResults() {
        int metricsDeleted = metricHistoryMapper.delete(new LambdaQueryWrapper<GridTransformerMetricHistory>()
                .lt(
                        GridTransformerMetricHistory::getWindowEnd,
                        LocalDateTime.now().minusDays(metricRetentionDays)));
        int alertsDeleted = alertMapper.delete(new LambdaQueryWrapper<GridRiskAlert>()
                .lt(GridRiskAlert::getEventTime, LocalDateTime.now().minusDays(alertRetentionDays)));
        alertsDeleted += alertMapper.deleteWithoutActiveMonitoring();
        alertsDeleted += trimAlertsToLimit();
        if (metricsDeleted > 0 || alertsDeleted > 0) {
            LOGGER.info("实时计算历史清理完成：指标删除{}条，预警删除{}条", metricsDeleted, alertsDeleted);
        }
    }

    public int trimAlertsToLimit() {
        return alertMapper.deleteOlderThanLatestPerUser(alertMaxRecords);
    }
}
