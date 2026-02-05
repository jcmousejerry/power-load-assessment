package com.chen.server.service;

import com.chen.server.entity.TaskNotification;

public interface TaskNotificationService {

    /**
     * 通知任务完成
     * @param taskId 任务ID
     * @param taskType 任务类型
     * @param userId 用户ID
     */
    void notifyTaskCompleted(Long taskId, Integer taskType, Long userId);

    /**
     * 通知任务失败
     * @param taskId 任务ID
     * @param taskType 任务类型
     * @param userId 用户ID
     * @param errorMessage 错误信息
     */
    void notifyTaskFailed(Long taskId, Integer taskType, Long userId, String errorMessage);
}
