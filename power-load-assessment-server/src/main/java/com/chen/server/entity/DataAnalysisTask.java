package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 数据分析任务信息实体类
 */
@Data
@TableName("data_analysis_task")
public class DataAnalysisTask {

    /**
     * 任务信息主键
     */
    @TableId(value = "id", type = IdType.NONE)
    private Long id;

    /**
     * 创建任务的用户id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 任务关联的数据集id
     */
    @TableField("dataset_id")
    private Long datasetId;

    /**
     * 任务类型（0:基本统计特征提取, 1:用户聚类, 2:负荷预测）
     */
    @TableField("task_type")
    private Integer taskType;

    /**
     * 任务状态（0:未完成, 1:已完成）
     */
    @TableField("status")
    private Integer status;

    /**
     * 任务创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 任务信息数据更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
