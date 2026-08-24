package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("outbox_event")
public class OutboxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String topicName;
    private String messageKey;
    private String payloadJson;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
