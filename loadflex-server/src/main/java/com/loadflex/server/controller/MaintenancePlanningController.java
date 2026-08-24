package com.loadflex.server.controller;

import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.service.GridControlService;
import com.loadflex.server.service.MaintenancePlanningService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grid/planning")
public class MaintenancePlanningController {
    private final MaintenancePlanningService planningService;
    private final GridControlService controlService;

    public MaintenancePlanningController(
            MaintenancePlanningService planningService, GridControlService controlService) {
        this.planningService = planningService;
        this.controlService = controlService;
    }

    @GetMapping("/topology")
    public ApiResponse<Map<String, Object>> topology() {
        return ApiResponse.ok(planningService.topology());
    }

    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans() {
        return ApiResponse.ok(planningService.listPlans());
    }

    @PostMapping("/plans")
    public ApiResponse<Map<String, Object>> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return ApiResponse.ok(
                "检修计划草稿已创建",
                planningService.createPlan(
                        request.planName(),
                        request.reason(),
                        request.outageDeviceType(),
                        request.outageDeviceCode(),
                        request.startTime(),
                        request.endTime(),
                        request.safetyLimit(),
                        request.loadProfileType(),
                        request.historicalDate(),
                        request.responsiblePerson()));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<Map<String, Object>> plan(@PathVariable long planId) {
        return ApiResponse.ok(planningService.planDetail(planId));
    }

    @DeleteMapping("/plans/{planId}")
    public ApiResponse<Void> deletePlan(@PathVariable long planId) {
        planningService.deletePlan(planId);
        return ApiResponse.ok("历史分析及其仿真、调控记录已删除", null);
    }

    @PutMapping("/plans/{planId}")
    public ApiResponse<Map<String, Object>> updatePlan(
            @PathVariable long planId, @Valid @RequestBody UpdatePlanRequest request) {
        return ApiResponse.ok(planningService.updatePlan(
                planId,
                request.planName(),
                request.reason(),
                request.startTime(),
                request.endTime(),
                request.safetyLimit(),
                request.loadProfileType(),
                request.historicalDate(),
                request.responsiblePerson()));
    }

    @GetMapping("/plans/{planId}/impact")
    public ApiResponse<Map<String, Object>> impact(@PathVariable long planId) {
        return ApiResponse.ok(planningService.impact(planId));
    }

    @PostMapping("/plans/{planId}/candidates")
    public ApiResponse<List<Map<String, Object>>> candidates(@PathVariable long planId) {
        return ApiResponse.ok("候选转供方案已生成", planningService.generateCandidates(planId));
    }

    @PostMapping("/plans/{planId}/submit")
    public ApiResponse<Map<String, Object>> submit(
            @PathVariable long planId, @Valid @RequestBody SubmitRequest request) {
        return ApiResponse.ok("检修计划已提交审核", planningService.submit(planId, request.scenarioId(), request.comment()));
    }

    @PostMapping("/plans/{planId}/review")
    public ApiResponse<Map<String, Object>> review(
            @PathVariable long planId, @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok("审核结果已保存", planningService.review(planId, request.decision(), request.comment()));
    }

    @PostMapping("/plans/{planId}/archive")
    public ApiResponse<Map<String, Object>> archive(@PathVariable long planId) {
        return ApiResponse.ok("检修计划已归档", planningService.archive(planId));
    }

    @GetMapping("/scenarios/{scenarioId}")
    public ApiResponse<Map<String, Object>> scenario(@PathVariable long scenarioId) {
        return ApiResponse.ok(planningService.scenarioDetail(scenarioId));
    }

    @PostMapping("/scenarios/{scenarioId}/clone")
    public ApiResponse<Map<String, Object>> cloneScenario(@PathVariable long scenarioId) {
        return ApiResponse.ok("方案副本已创建", planningService.cloneScenario(scenarioId));
    }

    @PutMapping("/scenarios/{scenarioId}/steps")
    public ApiResponse<Map<String, Object>> saveSteps(
            @PathVariable long scenarioId, @RequestBody SaveStepsRequest request) {
        return ApiResponse.ok("操作步骤已保存", planningService.saveSteps(scenarioId, request.steps()));
    }

    @PostMapping("/scenarios/{scenarioId}/simulate")
    public ApiResponse<Map<String, Object>> simulate(@PathVariable long scenarioId) {
        return ApiResponse.ok("仿真任务已创建", planningService.startSimulation(scenarioId));
    }

    @GetMapping("/simulations/{taskId}")
    public ApiResponse<Map<String, Object>> simulation(@PathVariable long taskId) {
        return ApiResponse.ok(planningService.simulationTask(taskId));
    }

    @GetMapping("/simulations/{taskId}/points")
    public ApiResponse<List<Map<String, Object>>> simulationPoints(@PathVariable long taskId) {
        return ApiResponse.ok(planningService.simulationPoints(taskId));
    }

    @PostMapping("/simulations/{taskId}/cancel")
    public ApiResponse<Map<String, Object>> cancelSimulation(@PathVariable long taskId) {
        return ApiResponse.ok(planningService.cancelSimulation(taskId));
    }

    @GetMapping("/scenarios/compare")
    public ApiResponse<List<Map<String, Object>>> compare(@RequestParam("scenarioIds") List<Long> scenarioIds) {
        return ApiResponse.ok(planningService.compareScenarios(scenarioIds));
    }

    @PostMapping("/scenarios/{scenarioId}/control-sessions")
    public ApiResponse<Map<String, Object>> createControlSession(
            @PathVariable long scenarioId, @RequestBody(required = false) CreateControlSessionRequest request) {
        return ApiResponse.ok(
                "仿真调控台已创建", controlService.create(scenarioId, request == null ? null : request.sessionName()));
    }

    @GetMapping("/plans/{planId}/control-sessions")
    public ApiResponse<List<Map<String, Object>>> controlSessions(@PathVariable long planId) {
        return ApiResponse.ok(controlService.listForPlan(planId));
    }

    @GetMapping("/control-sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> controlSession(@PathVariable long sessionId) {
        return ApiResponse.ok(controlService.detail(sessionId));
    }

    @PostMapping("/control-sessions/{sessionId}/actions")
    public ApiResponse<Map<String, Object>> executeControlAction(
            @PathVariable long sessionId, @Valid @RequestBody ControlActionRequest request) {
        return ApiResponse.ok(
                "仿真调控操作已执行", controlService.execute(sessionId, request.actionType(), request.idempotencyKey()));
    }

    @PostMapping("/control-sessions/{sessionId}/rollback")
    public ApiResponse<Map<String, Object>> rollbackControl(
            @PathVariable long sessionId, @RequestBody(required = false) RollbackRequest request) {
        return ApiResponse.ok(
                "已恢复原供电状态", controlService.rollback(sessionId, request == null ? null : request.idempotencyKey()));
    }

    public record CreatePlanRequest(
            @NotBlank String planName,
            @NotBlank String reason,
            @NotBlank String outageDeviceType,
            @NotBlank String outageDeviceCode,
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime,
            @NotNull BigDecimal safetyLimit,
            @NotBlank String loadProfileType,
            LocalDate historicalDate,
            String responsiblePerson) {}

    public record UpdatePlanRequest(
            @NotBlank String planName,
            @NotBlank String reason,
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime,
            @NotNull BigDecimal safetyLimit,
            @NotBlank String loadProfileType,
            LocalDate historicalDate,
            String responsiblePerson) {}

    public record SaveStepsRequest(List<Map<String, String>> steps) {}

    public record SubmitRequest(@NotNull Long scenarioId, String comment) {}

    public record ReviewRequest(@NotBlank String decision, String comment) {}

    public record CreateControlSessionRequest(String sessionName) {}

    public record ControlActionRequest(@NotBlank String actionType, String idempotencyKey) {}

    public record RollbackRequest(String idempotencyKey) {}
}
