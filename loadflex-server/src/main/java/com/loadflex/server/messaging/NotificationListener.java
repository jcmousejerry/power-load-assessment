package com.loadflex.server.messaging;

import com.loadflex.common.messaging.TaskNotification;
import com.loadflex.server.service.QueueEstimateService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {
    private final SimpMessagingTemplate messaging;
    private final QueueEstimateService queueEstimateService;

    public NotificationListener(SimpMessagingTemplate messaging, QueueEstimateService queueEstimateService) {
        this.messaging = messaging;
        this.queueEstimateService = queueEstimateService;
    }

    @KafkaListener(topics = "${loadflex.kafka.notification-topic}", groupId = "loadflex-server-notification")
    public void onNotification(TaskNotification notification) {
        queueEstimateService.synchronizeTask(notification.getTaskId());
        messaging.convertAndSend("/topic/user/" + notification.getUserId(), notification);
    }
}
