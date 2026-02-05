package com.chen.server.entity;

import com.chen.server.enums.TaskStatus;
import com.chen.server.enums.TaskType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskNotification {
    private Long taskId;
    private Integer taskType;
    private String taskTypeName;
    private Integer status;
    private String statusDescription;
    private String message;
    private Long userId; // 推送给特定用户的标识
    private Long timestamp;

    public TaskNotification(Long taskId, Integer taskType, Integer status, String message, Long userId) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = status;
        this.message = message;
        this.userId = userId;
        this.timestamp = System.currentTimeMillis();

        TaskType type = TaskType.fromCode(taskType);
        this.taskTypeName = type != null ? type.getDescription() : "未知任务类型";

        TaskStatus taskStatus = TaskStatus.fromCode(status);
        this.statusDescription = taskStatus != null ? taskStatus.getDescription() : "未知状态";
    }
}
