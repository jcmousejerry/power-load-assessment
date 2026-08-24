package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("analysis_task")
public class AnalysisTask {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long datasetId;
    private Long pipelineId;
    private Long snapshotId;
    private String taskType;
    private String taskName;
    private String status;
    private String stage;
    private BigDecimal progress;
    private Integer priority;
    private String resourcePool;
    private String parametersJson;
    private String algorithmVersion;
    private Integer queuePosition;

    @TableField(exist = false)
    private Integer queuedAheadCount;

    @TableField(exist = false)
    private Long estimatedWaitSeconds;

    @TableField(exist = false)
    private LocalDateTime estimatedStartAt;

    private LocalDateTime etaP50At;
    private LocalDateTime etaP90At;
    private String etaConfidence;
    private Integer currentAttempt;
    private Boolean cancelRequested;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    private String idempotencyKey;
    private LocalDateTime queuedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
