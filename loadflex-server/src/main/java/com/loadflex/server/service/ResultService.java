package com.loadflex.server.service;

import com.loadflex.common.entity.AnalysisResult;
import java.util.List;

public interface ResultService {
    List<AnalysisResult> list(Long datasetId, Long pipelineId);

    AnalysisResult get(Long id);
}
