package com.loadflex.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.TaskDependency;
import com.loadflex.common.entity.TaskEvent;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.common.mapper.TaskDependencyMapper;
import com.loadflex.common.mapper.TaskEventMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DependencyDispatchService {
    private static final Set<String> UNSUCCESSFUL_TERMINAL = Set.of("FAILED", "CANCELLED", "REJECTED");

    private final AnalysisTaskMapper taskMapper;
    private final TaskDependencyMapper dependencyMapper;
    private final TaskEventMapper taskEventMapper;
    private final QueueEstimateService queueEstimateService;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messaging;

    public DependencyDispatchService(
            AnalysisTaskMapper taskMapper,
            TaskDependencyMapper dependencyMapper,
            TaskEventMapper taskEventMapper,
            QueueEstimateService queueEstimateService,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messaging) {
        this.taskMapper = taskMapper;
        this.dependencyMapper = dependencyMapper;
        this.taskEventMapper = taskEventMapper;
        this.queueEstimateService = queueEstimateService;
        this.objectMapper = objectMapper;
        this.messaging = messaging;
    }

    @Scheduled(fixedDelay = 1500)
    @Transactional(rollbackFor = Exception.class)
    public void dispatchReadyTasks() throws Exception {
        List<AnalysisTask> waitingTasks = taskMapper.selectList(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getStatus, "WAITING_DEPENDENCY")
                .orderByAsc(AnalysisTask::getId));
        for (AnalysisTask task : waitingTasks) {
            List<TaskDependency> dependencies = dependencyMapper.selectList(
                    new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getChildTaskId, task.getId()));
            List<AnalysisTask> prerequisites = dependencies.stream()
                    .map(item -> taskMapper.selectById(item.getPrerequisiteTaskId()))
                    .toList();
            if (prerequisites.stream()
                    .anyMatch(item -> item == null || UNSUCCESSFUL_TERMINAL.contains(item.getStatus()))) {
                rejectTask(task);
            } else if (!prerequisites.isEmpty()
                    && prerequisites.stream().allMatch(item -> "SUCCEEDED".equals(item.getStatus()))) {
                enqueueTask(task);
            }
        }
    }

    private void rejectTask(AnalysisTask task) throws Exception {
        task.setStatus("REJECTED");
        task.setStage("UPSTREAM_FAILED");
        task.setErrorCode("UPSTREAM_TASK_FAILED");
        task.setErrorMessage("上游任务未成功，当前任务不会执行");
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        addTaskEvent(task, "REJECTED", Map.of("message", task.getErrorMessage()));
        pushUpdate(task, task.getErrorMessage());
    }

    private void enqueueTask(AnalysisTask task) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("QUEUED");
        task.setStage("WAITING_RESOURCE");
        task.setProgress(BigDecimal.ZERO);
        task.setQueuedAt(now);
        task.setQueuePosition(1);
        task.setQueuedAheadCount(0);
        task.setEstimatedWaitSeconds(0L);
        task.setEstimatedStartAt(now);
        taskMapper.updateById(task);
        addTaskEvent(task, "DEPENDENCIES_SATISFIED", Map.of("message", "全部上游任务成功，已进入执行队列"));
        queueEstimateService.synchronizeAfterCommit(task.getId());
        pushUpdate(task, "上游任务已完成，当前任务进入队列");
    }

    private void addTaskEvent(AnalysisTask task, String eventType, Object payload) throws Exception {
        TaskEvent event = new TaskEvent();
        event.setTaskId(task.getId());
        event.setAttemptNo(task.getCurrentAttempt());
        event.setEventSeq(taskEventMapper.maxSeq(task.getId()) + 1);
        event.setEventType(eventType);
        event.setPayloadJson(objectMapper.writeValueAsString(payload));
        taskEventMapper.insert(event);
    }

    private void pushUpdate(AnalysisTask task, String message) {
        messaging.convertAndSend(
                "/topic/user/" + task.getUserId(),
                Map.of(
                        "taskId", task.getId(),
                        "userId", task.getUserId(),
                        "status", task.getStatus(),
                        "stage", task.getStage(),
                        "progress", task.getProgress(),
                        "message", message));
    }
}
