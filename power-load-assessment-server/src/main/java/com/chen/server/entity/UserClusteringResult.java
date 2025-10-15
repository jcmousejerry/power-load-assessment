package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_clustering_result")
public class UserClusteringResult {

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
     * 聚簇id
     */
    @TableField("cluster_id")
    private Integer clusterId;

    /**
     * 聚簇用户信息（字符串）
     */
    @TableField("cluster_user_info")
    private String clusterUserInfo;

    /**
     * 聚簇负荷模式数据（字符串）
     */
    @TableField("cluster_load_pattern")
    private String clusterLoadPattern;

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
