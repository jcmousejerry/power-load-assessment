package com.loadflex.server.controller;

import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.TaskEvent;
import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.dto.CreatePipelineDTO;
import com.loadflex.server.dto.CreateTaskDTO;
import com.loadflex.server.service.TaskService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<List<AnalysisTask>> list(
            @RequestParam(value = "datasetId", required = false) Long datasetId,
            @RequestParam(value = "pipelineId", required = false) Long pipelineId) {
        return ApiResponse.ok(taskService.list(datasetId, pipelineId));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalysisTask> get(@PathVariable Long id) {
        return ApiResponse.ok(taskService.get(id));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<TaskEvent>> events(@PathVariable Long id) {
        return ApiResponse.ok(taskService.events(id));
    }

    @PostMapping
    public ApiResponse<AnalysisTask> create(
            @RequestBody CreateTaskDTO request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
            throws Exception {
        return ApiResponse.ok(
                "任务创建成功",
                taskService.create(
                        request.getTaskType(),
                        request.getTaskName(),
                        request.getDatasetId(),
                        request.getParameters(),
                        idempotencyKey));
    }

    @PostMapping("/pipeline")
    public ApiResponse<List<AnalysisTask>> createPipeline(@RequestBody CreatePipelineDTO request) throws Exception {
        return ApiResponse.ok(
                "分析流水线创建成功",
                taskService.createPipeline(
                        request.getDatasetId(),
                        request.getPipelineName(),
                        request.getClusterCount(),
                        request.getForecastSteps(),
                        request.getBaselineType(),
                        request.getMaximumAdjustableKw()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AnalysisTask> cancel(@PathVariable Long id) throws Exception {
        return ApiResponse.ok("取消请求已提交", taskService.cancel(id));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AnalysisTask> retry(@PathVariable Long id) throws Exception {
        return ApiResponse.ok("任务已重新进入队列", taskService.retry(id));
    }
}
