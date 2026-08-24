package com.loadflex.consumer.dto;

import lombok.Data;

@Data
public class AlgorithmRequest {
    private Long taskId;
    private Long datasetId;
    private String taskType;
    private String objectKey;
    private String mappingJson;
    private String parametersJson;
    private String upstreamResultsJson;
    private String algorithmVersion;
}
