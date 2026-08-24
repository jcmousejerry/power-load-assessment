package com.loadflex.server.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record GridTelemetrySnapshot(
        String transformerCode,
        LocalDateTime sampleTime,
        BigDecimal totalLoadKw,
        BigDecimal ratedCapacityKw,
        Map<String, BigDecimal> meterLoadsKw) {}
