package com.chen.server.service.impl;

import com.chen.server.entity.TaskNotification;
import com.chen.server.enums.TaskStatus;
import com.chen.server.service.TaskNotificationService;
import com.chen.server.utils.TaskNotificationWebSocket;
import org.springframework.stereotype.Service;

@Service
public class TaskNotificationServiceImpl implements TaskNotificationService {

    @Override
    public void notifyTaskCompleted(Long taskId, Integer taskType, Long userId) {
        TaskNotification notification = new TaskNotification(
            taskId,
            taskType,
            TaskStatus.COMPLETED.getCode(),
            "数据分析任务已完成",
            userId
        );

        TaskNotificationWebSocket.sendTaskNotification(userId, notification);
    }

    @Override
    public void notifyTaskFailed(Long taskId, Integer taskType, Long userId, String errorMessage) {
        TaskNotification notification = new TaskNotification(
            taskId,
            taskType,
            TaskStatus.PENDING.getCode(), // 或者定义一个失败状态
            "数据分析任务执行失败: " + errorMessage,
            userId
        );

        TaskNotificationWebSocket.sendTaskNotification(userId, notification);
    }
}
