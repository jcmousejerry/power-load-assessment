package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("task_event")
public class TaskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Integer attemptNo;
    private Long eventSeq;
    private String eventType;
    private String payloadJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
