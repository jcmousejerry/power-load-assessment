package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("grid_user_transformer_monitor")
public class GridUserTransformerMonitor {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String transformerCode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
