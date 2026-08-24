package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("analysis_result")
public class AnalysisResult {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String resultType;
    private String summaryJson;
    private String artifactObjectKey;
    private String artifactSha256;
    private String algorithmVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
