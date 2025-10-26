package com.chen.server.dto;

import lombok.Data;

/**
 * 数据分析任务DTO
 */
@Data
public class DataAnalysisTaskDTO {
    /**
     * 数据集ID
     */
    private Long datasetId;

    /**
     * 数据分析任务类型
     * 0:基本统计特征提取
     * 1:用户聚类
     * 2:负荷预测
     */
    private Integer taskType;

    /**
     * 聚类数量（用于用户聚类任务）
     */
    private Integer clusterCount;

    /**
     * 预测时间步数（用于负荷预测任务）
     */
    private Integer forecastSteps;

}
