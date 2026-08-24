package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("grid_risk_alert")
public class GridRiskAlert {
    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonIgnore
    private Long userId;

    private String eventId;
    private String transformerCode;
    private String alertType;
    private String severity;
    private BigDecimal currentLoadKw;
    private BigDecimal ratedCapacityKw;
    private BigDecimal loadRate;
    private BigDecimal historicalUpperKw;
    private Integer consecutiveWindows;
    private LocalDateTime eventTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
