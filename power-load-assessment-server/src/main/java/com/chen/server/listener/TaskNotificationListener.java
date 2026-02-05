package com.chen.server.listener;

import com.chen.server.entity.TaskNotification;
import com.chen.server.enums.TaskStatus;
import com.chen.server.utils.TaskNotificationWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TaskNotificationListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskNotificationListener.class);

    @KafkaListener(topics = "task-completion-notification", groupId = "task-notification-group")
    public void handleTaskNotification(Map<String, Object> message) {
        try {
            Long taskId = ((Number) message.get("taskId")).longValue();
            Integer taskType = (Integer) message.get("taskType");
            Long userId = ((Number) message.get("userId")).longValue();
            Integer status = (Integer) message.get("status");
            String messageContent = (String) message.get("message");

            // 创建通知对象
            TaskNotification notification = new TaskNotification(
                taskId,
                taskType,
                status,
                messageContent,
                userId
            );

            // 通过WebSocket推送给用户
            TaskNotificationWebSocket.sendTaskNotification(userId, notification);

            logger.info("任务完成通知已推送给用户 {}: taskId={}, taskType={}",
                       userId, taskId, taskType);
        } catch (Exception e) {
            logger.error("处理任务完成通知失败", e);
        }
    }
}
