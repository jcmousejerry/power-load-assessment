package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("analysis_pipeline")
public class AnalysisPipeline {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long datasetId;
    private String pipelineName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
