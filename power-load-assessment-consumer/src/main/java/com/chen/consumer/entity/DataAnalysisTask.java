package com.chen.consumer.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("data_analysis_task")
public class DataAnalysisTask {

    @TableId(value = "id", type = IdType.NONE)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("dataset_id")
    private Long datasetId;

    @TableField("task_type")
    private Integer taskType;

    @TableField("status")
    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
