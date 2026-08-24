package com.loadflex.server.service;

import com.loadflex.common.entity.AnalysisPipeline;
import java.util.List;

public interface PipelineService {
    List<AnalysisPipeline> list(Long datasetId);
}
