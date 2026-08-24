package com.loadflex.server.service;

import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.TaskEvent;
import java.util.List;
import java.util.Map;

public interface TaskService {
    AnalysisTask create(String type, String name, Long datasetId, Map<String, Object> parameters, String idempotencyKey)
            throws Exception;

    List<AnalysisTask> createPipeline(
            Long datasetId,
            String pipelineName,
            Integer clusterCount,
            Integer forecastSteps,
            String baselineType,
            Double maximumAdjustableKw)
            throws Exception;

    AnalysisTask cancel(Long id) throws Exception;

    AnalysisTask retry(Long id) throws Exception;

    List<AnalysisTask> list(Long datasetId, Long pipelineId);

    AnalysisTask get(Long id);

    List<TaskEvent> events(Long id);
}
