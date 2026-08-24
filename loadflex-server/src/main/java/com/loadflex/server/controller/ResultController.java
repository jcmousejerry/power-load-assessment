package com.loadflex.server.controller;

import com.loadflex.common.entity.AnalysisResult;
import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.service.ResultService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/results")
public class ResultController {
    private final ResultService resultService;

    public ResultController(ResultService resultService) {
        this.resultService = resultService;
    }

    @GetMapping
    public ApiResponse<List<AnalysisResult>> list(
            @RequestParam(value = "datasetId", required = false) Long datasetId,
            @RequestParam(value = "pipelineId", required = false) Long pipelineId) {
        return ApiResponse.ok(resultService.list(datasetId, pipelineId));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalysisResult> get(@PathVariable Long id) {
        return ApiResponse.ok(resultService.get(id));
    }
}
