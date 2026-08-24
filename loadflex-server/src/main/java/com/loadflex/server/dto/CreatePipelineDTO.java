package com.loadflex.server.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreatePipelineDTO {
    @NotNull(message = "请选择数据集")
    private Long datasetId;

    private String pipelineName;

    @Min(value = 2, message = "聚类数量不能小于2")
    @Max(value = 6, message = "聚类数量不能大于6")
    private Integer clusterCount = 3;

    @Min(value = 1, message = "预测点数不能小于1")
    @Max(value = 192, message = "预测点数不能大于192")
    private Integer forecastSteps = 96;

    private String baselineType = "typical";
    private Double maximumAdjustableKw;
}
