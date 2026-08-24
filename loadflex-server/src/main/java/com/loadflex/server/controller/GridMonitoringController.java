package com.loadflex.server.controller;

import com.loadflex.common.entity.GridRiskAlert;
import com.loadflex.common.entity.GridTransformer;
import com.loadflex.common.entity.GridTransformerMetric;
import com.loadflex.common.entity.GridTransformerMetricHistory;
import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.api.GridTelemetrySnapshot;
import com.loadflex.server.service.GridMonitoringService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grid")
public class GridMonitoringController {
    private final GridMonitoringService monitoringService;

    public GridMonitoringController(GridMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/transformers")
    public ApiResponse<List<GridTransformer>> transformers() {
        return ApiResponse.ok(monitoringService.transformers());
    }

    @GetMapping("/monitoring/transformers")
    public ApiResponse<List<GridTransformer>> monitoredTransformers() {
        return ApiResponse.ok(monitoringService.monitoredTransformers());
    }

    @PostMapping("/monitoring/transformers/{transformerCode}")
    public ApiResponse<GridTransformer> startMonitoring(@PathVariable String transformerCode) {
        return ApiResponse.ok(monitoringService.startMonitoring(transformerCode));
    }

    @DeleteMapping("/monitoring/transformers/{transformerCode}")
    public ApiResponse<GridTransformer> stopMonitoring(@PathVariable String transformerCode) {
        return ApiResponse.ok(monitoringService.stopMonitoring(transformerCode));
    }

    @GetMapping("/metrics")
    public ApiResponse<List<GridTransformerMetric>> metrics() {
        return ApiResponse.ok(monitoringService.latestMetrics());
    }

    @GetMapping("/telemetry/latest")
    public ApiResponse<List<GridTelemetrySnapshot>> latestTelemetry() {
        return ApiResponse.ok(monitoringService.latestTelemetry());
    }

    @GetMapping("/metrics/{transformerCode}/history")
    public ApiResponse<List<GridTransformerMetricHistory>> metricHistory(
            @PathVariable String transformerCode, @RequestParam(value = "minutes", required = false) Integer minutes) {
        return ApiResponse.ok(monitoringService.metricHistory(transformerCode, minutes));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<GridRiskAlert>> alerts(
            @RequestParam(value = "transformerCode", required = false) String transformerCode,
            @RequestParam(value = "alertType", required = false) String alertType,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(monitoringService.alerts(transformerCode, alertType, limit));
    }
}
