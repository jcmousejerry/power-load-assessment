package com.loadflex.server.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.OutboxEvent;
import com.loadflex.common.mapper.OutboxEventMapper;
import com.loadflex.common.messaging.TaskCommand;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_RETRY_COUNT = 20;

    private final OutboxEventMapper outboxEventMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(
            OutboxEventMapper outboxEventMapper,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = findPendingEvents();
        for (OutboxEvent outboxEvent : pendingEvents) {
            try {
                publish(outboxEvent);
            } catch (Exception exception) {
                recordFailure(outboxEvent);
                LOGGER.warn(
                        "Outbox消息发送失败，eventId={}, retryCount={}",
                        outboxEvent.getEventId(),
                        outboxEvent.getRetryCount(),
                        exception);
            }
        }
    }

    private List<OutboxEvent> findPendingEvents() {
        LocalDateTime now = LocalDateTime.now();
        return outboxEventMapper.selectList(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getStatus, "NEW")
                .and(wrapper -> wrapper.isNull(OutboxEvent::getNextRetryAt).or().le(OutboxEvent::getNextRetryAt, now))
                .orderByAsc(OutboxEvent::getCreatedAt)
                .last("LIMIT 50"));
    }

    private void publish(OutboxEvent outboxEvent) throws Exception {
        TaskCommand taskCommand = objectMapper.readValue(outboxEvent.getPayloadJson(), TaskCommand.class);
        kafkaTemplate
                .send(outboxEvent.getTopicName(), outboxEvent.getMessageKey(), taskCommand)
                .get();

        outboxEvent.setStatus("SENT");
        outboxEvent.setPublishedAt(LocalDateTime.now());
        outboxEventMapper.updateById(outboxEvent);
    }

    private void recordFailure(OutboxEvent outboxEvent) {
        int retryCount = outboxEvent.getRetryCount() + 1;
        outboxEvent.setRetryCount(retryCount);
        outboxEvent.setStatus(retryCount >= MAX_RETRY_COUNT ? "FAILED" : "NEW");
        outboxEvent.setNextRetryAt(LocalDateTime.now().plusSeconds(Math.min(60, retryCount * 2L)));
        outboxEventMapper.updateById(outboxEvent);
    }
}
