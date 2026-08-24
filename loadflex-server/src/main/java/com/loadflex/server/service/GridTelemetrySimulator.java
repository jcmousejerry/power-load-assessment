package com.loadflex.server.service;

import com.loadflex.common.entity.GridTransformer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "loadflex.grid.simulator-enabled", havingValue = "true", matchIfMissing = true)
public class GridTelemetrySimulator {
    private final GridMonitoringService monitoringService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String telemetryTopic;
    private final AtomicLong sequence = new AtomicLong();

    public GridTelemetrySimulator(
            GridMonitoringService monitoringService,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${loadflex.grid.telemetry-topic:grid-telemetry-raw}") String telemetryTopic) {
        this.monitoringService = monitoringService;
        this.kafkaTemplate = kafkaTemplate;
        this.telemetryTopic = telemetryTopic;
    }

    @Scheduled(fixedDelayString = "${loadflex.grid.simulator-interval-ms:2000}")
    public void publishTelemetry() {
        long tick = sequence.getAndIncrement();
        long cycle = tick % 45;
        long eventTime = Instant.now().toEpochMilli();
        for (GridTransformer transformer : monitoringService.systemMonitoredTransformers()) {
            double totalLoad = simulatedTotalLoad(transformer.getTransformerCode(), cycle, tick);
            Map<String, BigDecimal> meterLoads = new LinkedHashMap<>();
            for (int meterIndex = 1; meterIndex <= 4; meterIndex++) {
                String meterId = transformer.getTransformerCode() + "-M" + meterIndex;
                double meterLoad = round(totalLoad * meterShare(meterIndex));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventId", UUID.randomUUID().toString());
                event.put("transformerId", transformer.getTransformerCode());
                event.put("meterId", meterId);
                event.put("eventTimeEpochMs", eventTime);
                event.put("loadKw", meterLoad);
                event.put("ratedCapacityKw", transformer.getRatedCapacityKw());
                kafkaTemplate.send(telemetryTopic, transformer.getTransformerCode(), event);
                meterLoads.put(meterId, BigDecimal.valueOf(meterLoad));
            }
            monitoringService.recordTelemetrySnapshot(transformer, eventTime, meterLoads);
        }
    }

    private double simulatedTotalLoad(String transformerCode, long cycle, long tick) {
        double wave = Math.sin(tick / 4.0) * 18.0;
        if ("T001".equals(transformerCode)) {
            return cycle >= 12 && cycle < 30 ? 920.0 + wave : 500.0 + wave;
        }
        if ("T002".equals(transformerCode)) {
            return cycle >= 30 ? 850.0 + wave : 380.0 + wave;
        }
        return 610.0 + wave;
    }

    private double meterShare(int meterIndex) {
        return new double[] {0.0, 0.22, 0.24, 0.26, 0.28}[meterIndex];
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
