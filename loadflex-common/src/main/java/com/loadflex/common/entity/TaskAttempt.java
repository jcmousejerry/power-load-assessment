package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("task_attempt")
public class TaskAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Integer attemptNo;
    private String workerId;
    private String leaseId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime finishedAt;
    private Integer runtimeSeconds;
    private String errorCode;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
