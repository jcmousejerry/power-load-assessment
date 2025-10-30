package com.chen.consumer.enums;

/**
 * 数据分析任务类型枚举
 */
public enum TaskType {
    /**
     * 基本统计特征提取
     */
    STATISTICAL_FEATURE_EXTRACTION(0, "基本统计特征提取"),

    /**
     * 用户聚类
     */
    USER_CLUSTERING(1, "用户聚类"),

    /**
     * 负荷预测
     */
    LOAD_FORECASTING(2, "负荷预测");

    private final Integer code;
    private final String description;

    TaskType(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
