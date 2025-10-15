package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("dataset_info")
public class DatasetInfo {
    /**
     * 数据集信息表的主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 数据集名称
     */
    @TableField("name")
    private String name;

    /**
     * 数据集存储路径（数据集文件在minio中的存储路径）
     */
    @TableField("path")
    private String path;

    /**
     * 数据集关联的用户id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 数据集中数据的开始日期
     */
    @TableField("start_date")
    private LocalDate startDate;

    /**
     * 数据集中数据的结束日期
     */
    @TableField("end_date")
    private LocalDate endDate;

    /**
     * mysql表中数据创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * mysql表中数据更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
