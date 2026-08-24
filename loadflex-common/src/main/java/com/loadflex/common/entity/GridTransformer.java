package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("grid_transformer")
public class GridTransformer {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String transformerCode;
    private String transformerName;
    private String areaName;
    private String feederCode;
    private BigDecimal ratedCapacityKw;
    private String status;
    private Boolean monitoringEnabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
