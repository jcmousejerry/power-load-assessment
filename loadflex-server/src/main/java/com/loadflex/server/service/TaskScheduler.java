package com.loadflex.server.service;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispatches at most one task per resource pool at a time. */
@Component
public class TaskScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskScheduler.class);
    private static final String LOCK_KEY_PREFIX = "loadflex:schedule:lock:";
    private static final int MAX_STALE_ITEMS_PER_RUN = 20;

    private final QueueEstimateService queueEstimateService;
    private final TaskDispatchService taskDispatchService;
    private final StringRedisTemplate redisTemplate;

    public TaskScheduler(
            QueueEstimateService queueEstimateService,
            TaskDispatchService taskDispatchService,
            StringRedisTemplate redisTemplate) {
        this.queueEstimateService = queueEstimateService;
        this.taskDispatchService = taskDispatchService;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${loadflex.queue.dispatch-interval-ms:500}")
    public void dispatchWaitingTasks() {
        for (String pool : queueEstimateService.resourcePools()) {
            dispatchOne(pool);
        }
    }

    private void dispatchOne(String pool) {
        String lockKey = LOCK_KEY_PREFIX + pool;
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }
        try {
            if (!queueEstimateService.hasFreeSlot(pool)) {
                return;
            }
            for (int index = 0; index < MAX_STALE_ITEMS_PER_RUN; index++) {
                Long taskId = queueEstimateService.firstWaitingTaskId(pool);
                if (taskId == null || taskDispatchService.dispatch(taskId)) {
                    return;
                }
                queueEstimateService.synchronizeTask(taskId);
            }
        } catch (Exception exception) {
            LOGGER.warn("调度等待任务失败，resourcePool={}", pool, exception);
        } finally {
            Object currentToken = redisTemplate.opsForValue().get(lockKey);
            if (lockToken.equals(currentToken)) {
                redisTemplate.delete(lockKey);
            }
        }
    }
}
