package com.loadflex.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("task_dependency")
public class TaskDependency {
    private Long childTaskId;
    private Long prerequisiteTaskId;
    private LocalDateTime createdAt;
}
