package com.loadflex.server.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.GridRiskAlert;
import com.loadflex.common.entity.GridTransformerMetric;
import com.loadflex.common.entity.GridTransformerMetricHistory;
import com.loadflex.common.mapper.GridRiskAlertMapper;
import com.loadflex.common.mapper.GridTransformerMetricHistoryMapper;
import com.loadflex.common.mapper.GridTransformerMetricMapper;
import com.loadflex.server.service.GridDataRetentionService;
import com.loadflex.server.service.GridMonitoringService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class GridResultListener {
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper;
    private final GridTransformerMetricMapper metricMapper;
    private final GridTransformerMetricHistoryMapper metricHistoryMapper;
    private final GridRiskAlertMapper alertMapper;
    private final SimpMessagingTemplate messaging;
    private final GridMonitoringService monitoringService;
    private final GridDataRetentionService retentionService;

    public GridResultListener(
            ObjectMapper objectMapper,
            GridTransformerMetricMapper metricMapper,
            GridTransformerMetricHistoryMapper metricHistoryMapper,
            GridRiskAlertMapper alertMapper,
            SimpMessagingTemplate messaging,
            GridMonitoringService monitoringService,
            GridDataRetentionService retentionService) {
        this.objectMapper = objectMapper;
        this.metricMapper = metricMapper;
        this.metricHistoryMapper = metricHistoryMapper;
        this.alertMapper = alertMapper;
        this.messaging = messaging;
        this.monitoringService = monitoringService;
        this.retentionService = retentionService;
    }

    @KafkaListener(
            topics = "${loadflex.grid.metric-topic:grid-transformer-metric}",
            groupId = "loadflex-grid-metric-store",
            containerFactory = "gridKafkaListenerContainerFactory")
    public void onMetric(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        GridTransformerMetric metric = new GridTransformerMetric();
        metric.setTransformerCode(requiredText(node, "transformerCode"));
        if (!monitoringService.isMonitored(metric.getTransformerCode())) {
            return;
        }
        metric.setWindowStart(toLocalDateTime(node.path("windowStartEpochMs").asLong()));
        metric.setWindowEnd(toLocalDateTime(node.path("windowEndEpochMs").asLong()));
        metric.setCurrentLoadKw(decimal(node, "currentLoadKw"));
        metric.setRatedCapacityKw(decimal(node, "ratedCapacityKw"));
        metric.setLoadRate(decimal(node, "loadRate"));
        metric.setHistoricalUpperKw(nullableDecimal(node, "historicalUpperKw"));
        metric.setStatus(requiredText(node, "status"));
        metric.setConsecutiveAbnormalWindows(
                node.path("consecutiveAbnormalWindows").asInt());
        storeMetricHistory(metric);
        if (metricMapper.selectById(metric.getTransformerCode()) == null) {
            metricMapper.insert(metric);
        } else {
            metricMapper.updateById(metric);
        }
        messaging.convertAndSend("/topic/grid/metrics", metric);
    }

    private void storeMetricHistory(GridTransformerMetric metric) {
        Long existing = metricHistoryMapper.selectCount(new LambdaQueryWrapper<GridTransformerMetricHistory>()
                .eq(GridTransformerMetricHistory::getTransformerCode, metric.getTransformerCode())
                .eq(GridTransformerMetricHistory::getWindowEnd, metric.getWindowEnd()));
        if (existing > 0) {
            return;
        }
        GridTransformerMetricHistory history = new GridTransformerMetricHistory();
        history.setTransformerCode(metric.getTransformerCode());
        history.setWindowStart(metric.getWindowStart());
        history.setWindowEnd(metric.getWindowEnd());
        history.setCurrentLoadKw(metric.getCurrentLoadKw());
        history.setRatedCapacityKw(metric.getRatedCapacityKw());
        history.setLoadRate(metric.getLoadRate());
        history.setHistoricalUpperKw(metric.getHistoricalUpperKw());
        history.setStatus(metric.getStatus());
        history.setConsecutiveAbnormalWindows(metric.getConsecutiveAbnormalWindows());
        metricHistoryMapper.insert(history);
    }

    @KafkaListener(
            topics = "${loadflex.grid.alert-topic:grid-risk-alert}",
            groupId = "loadflex-grid-alert-store",
            containerFactory = "gridKafkaListenerContainerFactory")
    public void onAlert(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        String eventId = requiredText(node, "eventId");
        String transformerCode = requiredText(node, "transformerCode");
        if (!monitoringService.isMonitored(transformerCode)) {
            return;
        }
        for (Long userId : monitoringService.monitoringUserIds(transformerCode)) {
            Long existing = alertMapper.selectCount(new LambdaQueryWrapper<GridRiskAlert>()
                    .eq(GridRiskAlert::getUserId, userId)
                    .eq(GridRiskAlert::getEventId, eventId));
            if (existing > 0) {
                continue;
            }
            GridRiskAlert alert = new GridRiskAlert();
            alert.setUserId(userId);
            alert.setEventId(eventId);
            alert.setTransformerCode(transformerCode);
            alert.setAlertType(requiredText(node, "alertType"));
            alert.setSeverity(requiredText(node, "severity"));
            alert.setCurrentLoadKw(decimal(node, "currentLoadKw"));
            alert.setRatedCapacityKw(decimal(node, "ratedCapacityKw"));
            alert.setLoadRate(decimal(node, "loadRate"));
            alert.setHistoricalUpperKw(nullableDecimal(node, "historicalUpperKw"));
            alert.setConsecutiveWindows(node.path("consecutiveWindows").asInt());
            alert.setEventTime(toLocalDateTime(node.path("eventTimeEpochMs").asLong()));
            alertMapper.insert(alert);
        }
        retentionService.trimAlertsToLimit();
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("电网消息缺少字段：" + field);
        }
        return value;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new IllegalArgumentException("电网消息缺少字段：" + field);
        }
        return node.get(field).decimalValue();
    }

    private BigDecimal nullableDecimal(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).decimalValue() : null;
    }

    private LocalDateTime toLocalDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), PROJECT_ZONE);
    }
}
