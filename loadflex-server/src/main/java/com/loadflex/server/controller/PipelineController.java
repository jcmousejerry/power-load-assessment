package com.loadflex.server.controller;

import com.loadflex.common.entity.AnalysisPipeline;
import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.service.PipelineService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {
    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public ApiResponse<List<AnalysisPipeline>> list(
            @RequestParam(value = "datasetId", required = false) Long datasetId) {
        return ApiResponse.ok(pipelineService.list(datasetId));
    }
}
