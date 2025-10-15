package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("statistical_feature_result")
public class StatisticalFeatureResult {

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
     * 数据集用户数量
     */
    @TableField("user_count")
    private Integer userCount;

    /**
     * 采样频率
     */
    @TableField("sampling_frequency")
    private Double samplingFrequency;

    /**
     * 峰值负荷
     */
    @TableField("peak_load")
    private Double peakLoad;

    /**
     * 谷值负荷
     */
    @TableField("valley_load")
    private Double valleyLoad;

    /**
     * 平均负荷
     */
    @TableField("average_load")
    private Double averageLoad;

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
