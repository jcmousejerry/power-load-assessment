package com.loadflex.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.server.service.TaskRuntimeEstimator.RuntimeEstimate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis-backed live queue index. MySQL remains the source of truth; Redis only stores the ordered waiting tasks,
 * their expected runtimes, and currently occupied resource slots.
 */
@Component
public class QueueEstimateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueEstimateService.class);
    private static final String QUEUE_KEY_PREFIX = "loadflex:schedule:queue:";
    private static final String RUNNING_KEY_PREFIX = "loadflex:schedule:running:";
    private static final String TASK_MEMBER_KEY = "loadflex:schedule:task-members";
    private static final String TASK_POOL_KEY = "loadflex:schedule:task-pools";
    private static final String TASK_RUNTIME_KEY = "loadflex:schedule:task-runtimes";
    private static final Set<String> DEFAULT_POOLS = Set.of("CPU_LIGHT", "CPU_HIGH_MEM");

    private final AnalysisTaskMapper taskMapper;
    private final TaskRuntimeEstimator runtimeEstimator;
    private final StringRedisTemplate redisTemplate;

    public QueueEstimateService(
            AnalysisTaskMapper taskMapper, TaskRuntimeEstimator runtimeEstimator, StringRedisTemplate redisTemplate) {
        this.taskMapper = taskMapper;
        this.runtimeEstimator = runtimeEstimator;
        this.redisTemplate = redisTemplate;
    }

    public void synchronizeAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronizeTask(taskId);
                }
            });
        } else {
            synchronizeTask(taskId);
        }
    }

    public void synchronizeTask(Long taskId) {
        try {
            AnalysisTask task = taskMapper.selectById(taskId);
            removeTask(taskId);
            if (task != null) {
                indexTask(task);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("更新 Redis 任务队列失败，taskId={}", taskId, exception);
        }
    }

    /** Calculates one task's queue information on demand without scanning the task table. */
    public void applyCurrentEstimate(AnalysisTask task) {
        if (task == null) {
            return;
        }
        if ("RUNNING".equals(task.getStatus()) || "CANCEL_REQUESTED".equals(task.getStatus())) {
            task.setQueuePosition(0);
            task.setQueuedAheadCount(0);
            task.setEstimatedWaitSeconds(0L);
            task.setEstimatedStartAt(task.getStartedAt());
            return;
        }
        if (!"QUEUED".equals(task.getStatus())) {
            return;
        }

        try {
            String taskId = task.getId().toString();
            Object mappingValue = redisTemplate.opsForHash().get(TASK_MEMBER_KEY, taskId);
            if (mappingValue == null) {
                return;
            }
            String mapping = mappingValue.toString();
            int separator = mapping.indexOf('|');
            if (separator < 1) {
                return;
            }
            String pool = mapping.substring(0, separator);
            String member = mapping.substring(separator + 1);
            Long rank = redisTemplate.opsForZSet().rank(queueKey(pool), member);
            if (rank == null) {
                return;
            }

            List<Integer> runtimesAhead = runtimesAhead(pool, rank);
            long waitSeconds = QueueWaitCalculator.estimateWaitSeconds(runningRemainingSeconds(pool), runtimesAhead);
            LocalDateTime estimatedStartAt = LocalDateTime.now().plusSeconds(waitSeconds);
            RuntimeValue ownRuntime = runtimeValue(redisTemplate.opsForHash().get(TASK_RUNTIME_KEY, taskId));

            task.setQueuePosition(Math.toIntExact(rank + 1));
            task.setQueuedAheadCount(Math.toIntExact(rank));
            task.setEstimatedWaitSeconds(waitSeconds);
            task.setEstimatedStartAt(estimatedStartAt);
            // Keep the old response fields compatible. They now represent the simplified expected start time.
            task.setEtaP50At(estimatedStartAt);
            task.setEtaP90At(null);
            if (ownRuntime != null) {
                task.setEtaConfidence(ownRuntime.confidence());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("读取 Redis 排队信息失败，taskId={}", task.getId(), exception);
        }
    }

    public Set<String> resourcePools() {
        Set<String> pools = new HashSet<>(DEFAULT_POOLS);
        try {
            List<Object> values = redisTemplate.opsForHash().values(TASK_POOL_KEY);
            values.stream().filter(Objects::nonNull).map(Object::toString).forEach(pools::add);
        } catch (RuntimeException exception) {
            LOGGER.warn("读取 Redis 资源池列表失败", exception);
        }
        return pools;
    }

    public boolean hasFreeSlot(String pool) {
        Long runningCount = redisTemplate.opsForZSet().zCard(runningKey(pool));
        return runningCount == null || runningCount == 0;
    }

    public Long firstWaitingTaskId(String pool) {
        Set<String> first = redisTemplate.opsForZSet().range(queueKey(pool), 0, 0);
        if (first == null || first.isEmpty()) {
            return null;
        }
        return Long.valueOf(taskIdFromMember(first.iterator().next()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        reconcileFromDatabase();
    }

    @Scheduled(
            initialDelayString = "${loadflex.queue.reconcile-initial-delay-ms:300000}",
            fixedDelayString = "${loadflex.queue.reconcile-interval-ms:300000}")
    public void reconcileFromDatabase() {
        try {
            List<AnalysisTask> activeTasks = taskMapper.selectList(new LambdaQueryWrapper<AnalysisTask>()
                    .in(AnalysisTask::getStatus, "QUEUED", "RUNNING", "CANCEL_REQUESTED")
                    .orderByAsc(AnalysisTask::getId));
            Set<String> pools = new HashSet<>(DEFAULT_POOLS);
            activeTasks.stream()
                    .map(AnalysisTask::getResourcePool)
                    .filter(Objects::nonNull)
                    .forEach(pools::add);

            List<String> keys = new ArrayList<>();
            for (String pool : pools) {
                keys.add(queueKey(pool));
                keys.add(runningKey(pool));
            }
            keys.add(TASK_MEMBER_KEY);
            keys.add(TASK_POOL_KEY);
            keys.add(TASK_RUNTIME_KEY);
            redisTemplate.delete(keys);
            activeTasks.forEach(this::indexTask);
            LOGGER.info("Redis 等待队列已从 MySQL 重建，activeTasks={}", activeTasks.size());
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis 等待队列重建失败，下次将自动重试", exception);
        }
    }

    private void indexTask(AnalysisTask task) {
        String pool = task.getResourcePool();
        if (pool == null || pool.isBlank()) {
            return;
        }
        String taskId = task.getId().toString();
        RuntimeEstimate estimate = runtimeEstimator.estimate(task.getTaskType());
        redisTemplate.opsForHash().put(TASK_POOL_KEY, taskId, pool);
        redisTemplate.opsForHash().put(TASK_RUNTIME_KEY, taskId, estimate.p50Seconds() + "|" + estimate.confidence());

        if ("QUEUED".equals(task.getStatus())) {
            String member = queueMember(task);
            redisTemplate.opsForZSet().add(queueKey(pool), member, 0D);
            redisTemplate.opsForHash().put(TASK_MEMBER_KEY, taskId, pool + "|" + member);
        } else if ("RUNNING".equals(task.getStatus()) || "CANCEL_REQUESTED".equals(task.getStatus())) {
            long startedAt = toEpochMillis(task.getStartedAt() == null ? LocalDateTime.now() : task.getStartedAt());
            redisTemplate.opsForZSet().add(runningKey(pool), taskId, startedAt + estimate.p50Seconds() * 1000D);
        }
    }

    private void removeTask(Long taskId) {
        String field = taskId.toString();
        Object mappingValue = redisTemplate.opsForHash().get(TASK_MEMBER_KEY, field);
        if (mappingValue != null) {
            String mapping = mappingValue.toString();
            int separator = mapping.indexOf('|');
            if (separator > 0) {
                redisTemplate
                        .opsForZSet()
                        .remove(queueKey(mapping.substring(0, separator)), mapping.substring(separator + 1));
            }
        }
        Object poolValue = redisTemplate.opsForHash().get(TASK_POOL_KEY, field);
        if (poolValue != null) {
            redisTemplate.opsForZSet().remove(runningKey(poolValue.toString()), field);
        }
        redisTemplate.opsForHash().delete(TASK_MEMBER_KEY, field);
        redisTemplate.opsForHash().delete(TASK_POOL_KEY, field);
        redisTemplate.opsForHash().delete(TASK_RUNTIME_KEY, field);
    }

    private List<Integer> runtimesAhead(String pool, long rank) {
        if (rank == 0) {
            return List.of();
        }
        Set<String> membersAhead = redisTemplate.opsForZSet().range(queueKey(pool), 0, rank - 1);
        if (membersAhead == null || membersAhead.isEmpty()) {
            return List.of();
        }
        List<String> taskIds = membersAhead.stream().map(this::taskIdFromMember).toList();
        List<Object> values = redisTemplate.opsForHash().multiGet(TASK_RUNTIME_KEY, new ArrayList<>(taskIds));
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::runtimeValue)
                .filter(Objects::nonNull)
                .map(RuntimeValue::seconds)
                .toList();
    }

    private long runningRemainingSeconds(String pool) {
        long now = Instant.now().toEpochMilli();
        Set<TypedTuple<String>> running = redisTemplate.opsForZSet().rangeWithScores(runningKey(pool), 0, -1);
        if (running == null || running.isEmpty()) {
            return 0;
        }
        return running.stream()
                .filter(item -> item.getScore() != null)
                .mapToLong(item -> Math.max(0, (long) Math.ceil((item.getScore() - now) / 1000D)))
                .sum();
    }

    private RuntimeValue runtimeValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String[] parts = value.toString().split("\\|", -1);
            return new RuntimeValue(Integer.parseInt(parts[0]), parts[1]);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String queueMember(AnalysisTask task) {
        long invertedPriority = (long) Integer.MAX_VALUE - (task.getPriority() == null ? 0 : task.getPriority());
        long queuedAt = toEpochMillis(task.getQueuedAt() == null ? LocalDateTime.now() : task.getQueuedAt());
        return String.format("%010d:%013d:%020d", invertedPriority, queuedAt, task.getId());
    }

    private String taskIdFromMember(String member) {
        return member.substring(member.lastIndexOf(':') + 1).replaceFirst("^0+(?!$)", "");
    }

    private String queueKey(String pool) {
        return QUEUE_KEY_PREFIX + pool;
    }

    private String runningKey(String pool) {
        return RUNNING_KEY_PREFIX + pool;
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private record RuntimeValue(int seconds, String confidence) {}
}
