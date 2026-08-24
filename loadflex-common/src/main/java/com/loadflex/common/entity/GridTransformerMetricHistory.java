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
@TableName("grid_transformer_metric_history")
public class GridTransformerMetricHistory {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String transformerCode;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private BigDecimal currentLoadKw;
    private BigDecimal ratedCapacityKw;
    private BigDecimal loadRate;
    private BigDecimal historicalUpperKw;
    private String status;
    private Integer consecutiveAbnormalWindows;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
