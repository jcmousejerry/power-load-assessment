package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("load_forecast_result")
public class LoadForecastResult {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 对应的任务id
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 负荷数据类型（0-历史负荷, 1-预测负荷）
     */
    @TableField("data_type")
    private Integer dataType;

    /**
     * 时间（历史时间或预测时间）
     */
    @TableField("forecast_time")
    private LocalDateTime forecastTime;

    /**
     * 负荷值（数值）
     */
    @TableField("load_value")
    private Double loadValue;

    /**
     * 任务执行结果信息数据创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 任务执行结果信息数据更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
