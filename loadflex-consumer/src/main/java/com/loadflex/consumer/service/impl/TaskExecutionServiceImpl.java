package com.loadflex.consumer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.AnalysisResult;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.ConsumedEvent;
import com.loadflex.common.entity.Dataset;
import com.loadflex.common.entity.TaskAttempt;
import com.loadflex.common.entity.TaskDependency;
import com.loadflex.common.entity.TaskEvent;
import com.loadflex.common.mapper.AnalysisResultMapper;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.common.mapper.ConsumedEventMapper;
import com.loadflex.common.mapper.DatasetMapper;
import com.loadflex.common.mapper.TaskAttemptMapper;
import com.loadflex.common.mapper.TaskDependencyMapper;
import com.loadflex.common.mapper.TaskEventMapper;
import com.loadflex.common.messaging.TaskCommand;
import com.loadflex.common.messaging.TaskNotification;
import com.loadflex.consumer.dto.AlgorithmRequest;
import com.loadflex.consumer.dto.AlgorithmResponse;
import com.loadflex.consumer.service.TaskExecutionService;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "CANCELLED", "REJECTED");

    private final AnalysisTaskMapper taskMapper;
    private final TaskAttemptMapper attemptMapper;
    private final TaskEventMapper taskEventMapper;
    private final TaskDependencyMapper dependencyMapper;
    private final AnalysisResultMapper resultMapper;
    private final DatasetMapper datasetMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final RestTemplate restTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String algorithmBaseUrl;
    private final String notificationTopic;

    public TaskExecutionServiceImpl(
            AnalysisTaskMapper taskMapper,
            TaskAttemptMapper attemptMapper,
            TaskEventMapper taskEventMapper,
            TaskDependencyMapper dependencyMapper,
            AnalysisResultMapper resultMapper,
            DatasetMapper datasetMapper,
            ConsumedEventMapper consumedEventMapper,
            RestTemplate restTemplate,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${loadflex.algorithm.base-url}") String algorithmBaseUrl,
            @Value("${loadflex.kafka.notification-topic}") String notificationTopic) {
        this.taskMapper = taskMapper;
        this.attemptMapper = attemptMapper;
        this.taskEventMapper = taskEventMapper;
        this.dependencyMapper = dependencyMapper;
        this.resultMapper = resultMapper;
        this.datasetMapper = datasetMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.restTemplate = restTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.algorithmBaseUrl = algorithmBaseUrl;
        this.notificationTopic = notificationTopic;
    }

    @Override
    public void execute(TaskCommand taskCommand) {
        if (alreadyProcessed(taskCommand.getMessageId())) {
            LOGGER.info("忽略已经处理的重复消息，messageId={}", taskCommand.getMessageId());
            return;
        }

        ConsumedEvent consumedEvent = createConsumedEvent(taskCommand);
        AnalysisTask task = taskMapper.selectById(taskCommand.getTaskId());
        if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) {
            markConsumed(consumedEvent, "PROCESSED", null);
            return;
        }
        if (Boolean.TRUE.equals(task.getCancelRequested())) {
            finishCancelledTask(task, consumedEvent);
            return;
        }

        TaskAttempt attempt = null;
        try {
            attempt = startTask(task);
            AlgorithmResponse response = callAlgorithm(taskCommand);
            if (response == null || !response.isSuccess()) {
                String message = response == null ? "算法服务没有返回结果" : response.getMessage();
                throw new IllegalStateException(message);
            }
            finishSuccessfulTask(task, attempt, response);
            markConsumed(consumedEvent, "PROCESSED", null);
        } catch (Exception exception) {
            LOGGER.error("分析任务执行失败，taskId={}", task.getId(), exception);
            finishFailedTask(task, attempt, exception);
            markConsumed(consumedEvent, "FAILED", safeMessage(exception));
        }
    }

    private boolean alreadyProcessed(String messageId) {
        ConsumedEvent event = consumedEventMapper.selectOne(
                new LambdaQueryWrapper<ConsumedEvent>().eq(ConsumedEvent::getEventId, messageId));
        return event != null && ("PROCESSING".equals(event.getStatus()) || "PROCESSED".equals(event.getStatus()));
    }

    private ConsumedEvent createConsumedEvent(TaskCommand taskCommand) {
        ConsumedEvent consumedEvent = new ConsumedEvent();
        consumedEvent.setEventId(taskCommand.getMessageId());
        consumedEvent.setTaskId(taskCommand.getTaskId());
        consumedEvent.setStatus("PROCESSING");
        consumedEventMapper.insert(consumedEvent);
        return consumedEvent;
    }

    private TaskAttempt startTask(AnalysisTask task) throws Exception {
        int attemptNumber = task.getCurrentAttempt() + 1;
        LocalDateTime now = LocalDateTime.now();

        task.setCurrentAttempt(attemptNumber);
        task.setStatus("RUNNING");
        task.setStage("PREPARING_DATA");
        task.setProgress(BigDecimal.valueOf(10));
        task.setStartedAt(now);
        task.setQueuePosition(0);
        taskMapper.updateById(task);

        TaskAttempt attempt = new TaskAttempt();
        attempt.setTaskId(task.getId());
        attempt.setAttemptNo(attemptNumber);
        attempt.setWorkerId(resolveWorkerId());
        attempt.setLeaseId(UUID.randomUUID().toString());
        attempt.setStatus("RUNNING");
        attempt.setStartedAt(now);
        attempt.setLastHeartbeatAt(now);
        attemptMapper.insert(attempt);

        addTaskEvent(task, "RUNNING", Map.of("stage", "PREPARING_DATA", "progress", 10));
        notifyUser(task, "任务已经开始执行");
        return attempt;
    }

    private AlgorithmResponse callAlgorithm(TaskCommand taskCommand) throws Exception {
        AnalysisTask task = taskMapper.selectById(taskCommand.getTaskId());
        task.setStage("ALGORITHM_RUNNING");
        task.setProgress(BigDecimal.valueOf(30));
        taskMapper.updateById(task);
        addTaskEvent(task, "PROGRESS", Map.of("stage", "ALGORITHM_RUNNING", "progress", 30));
        notifyUser(task, "算法正在计算");

        AlgorithmRequest request = new AlgorithmRequest();
        request.setTaskId(taskCommand.getTaskId());
        request.setDatasetId(taskCommand.getDatasetId());
        request.setTaskType(taskCommand.getTaskType());
        request.setObjectKey(taskCommand.getObjectKey());
        request.setMappingJson(taskCommand.getMappingJson());
        request.setParametersJson(taskCommand.getParametersJson());
        request.setUpstreamResultsJson(loadUpstreamResults(task.getId()));
        request.setAlgorithmVersion(taskCommand.getAlgorithmVersion());

        return restTemplate.postForObject(algorithmBaseUrl + "/api/v1/execute", request, AlgorithmResponse.class);
    }

    private String loadUpstreamResults(Long taskId) throws JsonProcessingException {
        Map<String, JsonNode> upstream = new LinkedHashMap<>();
        List<TaskDependency> dependencies = dependencyMapper.selectList(
                new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getChildTaskId, taskId));
        for (TaskDependency dependency : dependencies) {
            AnalysisTask prerequisite = taskMapper.selectById(dependency.getPrerequisiteTaskId());
            if (prerequisite == null) {
                continue;
            }
            AnalysisResult result = resultMapper.selectOne(new LambdaQueryWrapper<AnalysisResult>()
                    .eq(AnalysisResult::getTaskId, prerequisite.getId())
                    .eq(AnalysisResult::getResultType, prerequisite.getTaskType()));
            if (result != null) {
                upstream.put(prerequisite.getTaskType(), objectMapper.readTree(result.getSummaryJson()));
            }
            if (!"CLUSTER".equals(prerequisite.getTaskType())) {
                mergeTransitiveClusterResult(prerequisite.getId(), upstream);
            }
        }
        return objectMapper.writeValueAsString(upstream);
    }

    private void mergeTransitiveClusterResult(Long taskId, Map<String, JsonNode> upstream)
            throws JsonProcessingException {
        if (upstream.containsKey("CLUSTER")) {
            return;
        }
        List<TaskDependency> dependencies = dependencyMapper.selectList(
                new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getChildTaskId, taskId));
        for (TaskDependency dependency : dependencies) {
            AnalysisTask prerequisite = taskMapper.selectById(dependency.getPrerequisiteTaskId());
            if (prerequisite != null && "CLUSTER".equals(prerequisite.getTaskType())) {
                AnalysisResult result = resultMapper.selectOne(new LambdaQueryWrapper<AnalysisResult>()
                        .eq(AnalysisResult::getTaskId, prerequisite.getId())
                        .eq(AnalysisResult::getResultType, "CLUSTER"));
                if (result != null) {
                    upstream.put("CLUSTER", objectMapper.readTree(result.getSummaryJson()));
                }
            }
        }
    }

    private void finishSuccessfulTask(AnalysisTask task, TaskAttempt attempt, AlgorithmResponse response)
            throws Exception {
        AnalysisTask latestTask = taskMapper.selectById(task.getId());
        if (Boolean.TRUE.equals(latestTask.getCancelRequested())) {
            finishCancelledTask(latestTask, null);
            return;
        }

        String summaryJson = objectMapper.writeValueAsString(response.getSummary());
        saveResult(latestTask, response, summaryJson);
        updateDatasetProfile(latestTask, response.getSummary(), summaryJson);

        LocalDateTime finishedAt = LocalDateTime.now();
        latestTask.setStatus("SUCCEEDED");
        latestTask.setStage("COMPLETED");
        latestTask.setProgress(BigDecimal.valueOf(100));
        latestTask.setFinishedAt(finishedAt);
        latestTask.setErrorCode(null);
        latestTask.setErrorMessage(null);
        taskMapper.updateById(latestTask);

        attempt.setStatus("SUCCEEDED");
        attempt.setFinishedAt(finishedAt);
        attempt.setRuntimeSeconds(calculateRuntimeSeconds(attempt.getStartedAt(), finishedAt));
        attemptMapper.updateById(attempt);

        addTaskEvent(latestTask, "SUCCEEDED", Map.of("message", "任务执行成功", "progress", 100));
        notifyUser(latestTask, "任务执行成功");
    }

    private void saveResult(AnalysisTask task, AlgorithmResponse response, String summaryJson) {
        AnalysisResult result = resultMapper.selectOne(new LambdaQueryWrapper<AnalysisResult>()
                .eq(AnalysisResult::getTaskId, task.getId())
                .eq(AnalysisResult::getResultType, task.getTaskType()));
        if (result == null) {
            result = new AnalysisResult();
            result.setTaskId(task.getId());
            result.setResultType(task.getTaskType());
            result.setAlgorithmVersion(task.getAlgorithmVersion());
            result.setSummaryJson(summaryJson);
            result.setArtifactObjectKey(response.getArtifactObjectKey());
            result.setArtifactSha256(response.getArtifactSha256());
            resultMapper.insert(result);
        } else {
            result.setSummaryJson(summaryJson);
            result.setArtifactObjectKey(response.getArtifactObjectKey());
            result.setArtifactSha256(response.getArtifactSha256());
            resultMapper.updateById(result);
        }
    }

    private void updateDatasetProfile(AnalysisTask task, JsonNode summary, String summaryJson) {
        if (!"PROFILE".equals(task.getTaskType())) {
            return;
        }
        Dataset dataset = datasetMapper.selectById(task.getDatasetId());
        if (dataset == null) {
            return;
        }
        dataset.setProfileJson(summaryJson);
        dataset.setStatus("READY");
        if (summary != null) {
            dataset.setRowCount(readLong(summary, "rowCount"));
            dataset.setUserCount(readInteger(summary, "userCount"));
        }
        datasetMapper.updateById(dataset);
    }

    private void finishCancelledTask(AnalysisTask task, ConsumedEvent consumedEvent) {
        task.setStatus("CANCELLED");
        task.setStage("CANCELLED");
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        try {
            addTaskEvent(task, "CANCELLED", Map.of("message", "任务已取消"));
        } catch (Exception exception) {
            LOGGER.warn("记录取消事件失败，taskId={}", task.getId(), exception);
        }
        notifyUser(task, "任务已取消");
        if (consumedEvent != null) {
            markConsumed(consumedEvent, "PROCESSED", null);
        }
    }

    private void finishFailedTask(AnalysisTask task, TaskAttempt attempt, Exception exception) {
        LocalDateTime finishedAt = LocalDateTime.now();
        AnalysisTask latestTask = taskMapper.selectById(task.getId());
        latestTask.setStatus("FAILED");
        latestTask.setStage("FAILED");
        latestTask.setFinishedAt(finishedAt);
        latestTask.setErrorCode("ALGORITHM_EXECUTION_FAILED");
        latestTask.setErrorMessage(safeMessage(exception));
        taskMapper.updateById(latestTask);

        if (attempt != null) {
            attempt.setStatus("FAILED");
            attempt.setFinishedAt(finishedAt);
            attempt.setRuntimeSeconds(calculateRuntimeSeconds(attempt.getStartedAt(), finishedAt));
            attempt.setErrorCode("ALGORITHM_EXECUTION_FAILED");
            attempt.setErrorMessage(safeMessage(exception));
            attemptMapper.updateById(attempt);
        }

        try {
            addTaskEvent(latestTask, "FAILED", Map.of("message", safeMessage(exception)));
        } catch (Exception eventException) {
            LOGGER.warn("记录失败事件失败，taskId={}", task.getId(), eventException);
        }
        notifyUser(latestTask, "任务执行失败：" + safeMessage(exception));
    }

    private void addTaskEvent(AnalysisTask task, String eventType, Object payload) throws JsonProcessingException {
        TaskEvent taskEvent = new TaskEvent();
        taskEvent.setTaskId(task.getId());
        taskEvent.setAttemptNo(task.getCurrentAttempt());
        taskEvent.setEventSeq(taskEventMapper.maxSeq(task.getId()) + 1);
        taskEvent.setEventType(eventType);
        taskEvent.setPayloadJson(objectMapper.writeValueAsString(payload));
        taskEventMapper.insert(taskEvent);
    }

    private void notifyUser(AnalysisTask task, String message) {
        TaskNotification notification = new TaskNotification(
                task.getId(),
                task.getUserId(),
                task.getStatus(),
                task.getStage(),
                task.getProgress().doubleValue(),
                message);
        kafkaTemplate.send(notificationTopic, task.getId().toString(), notification);
    }

    private void markConsumed(ConsumedEvent event, String status, String errorMessage) {
        event.setStatus(status);
        event.setErrorMessage(errorMessage);
        consumedEventMapper.updateById(event);
    }

    private Long readLong(JsonNode summary, String fieldName) {
        JsonNode value = summary.get(fieldName);
        return value == null || !value.isNumber() ? null : value.longValue();
    }

    private Integer readInteger(JsonNode summary, String fieldName) {
        JsonNode value = summary.get(fieldName);
        return value == null || !value.isNumber() ? null : value.intValue();
    }

    private int calculateRuntimeSeconds(LocalDateTime startedAt, LocalDateTime finishedAt) {
        return (int) Duration.between(startedAt, finishedAt).getSeconds();
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "windows-worker";
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }
}
