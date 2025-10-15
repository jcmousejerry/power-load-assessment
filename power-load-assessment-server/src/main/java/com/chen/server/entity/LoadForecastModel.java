package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("load_forecast_model")
public class LoadForecastModel {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 模型对应的任务id
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 模型类型（0-单因素预测模型，1-多因素预测模型）
     */
    @TableField("model_type")
    private Integer modelType;

    /**
     * 模型存储路径（模型文件在MinIO中的存储路径）
     */
    @TableField("model_path")
    private String modelPath;

    /**
     * 模型信息数据创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 模型信息数据更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
