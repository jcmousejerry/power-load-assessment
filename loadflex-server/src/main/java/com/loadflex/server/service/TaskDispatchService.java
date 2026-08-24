package com.loadflex.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.Dataset;
import com.loadflex.common.entity.OutboxEvent;
import com.loadflex.common.entity.TaskEvent;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.common.mapper.DatasetMapper;
import com.loadflex.common.mapper.OutboxEventMapper;
import com.loadflex.common.mapper.TaskEventMapper;
import com.loadflex.common.messaging.TaskCommand;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Moves a selected waiting task to the execution phase and creates its Kafka Outbox message atomically. */
@Service
public class TaskDispatchService {

    private final AnalysisTaskMapper taskMapper;
    private final DatasetMapper datasetMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TaskEventMapper taskEventMapper;
    private final QueueEstimateService queueEstimateService;
    private final ObjectMapper objectMapper;
    private final String taskTopic;

    public TaskDispatchService(
            AnalysisTaskMapper taskMapper,
            DatasetMapper datasetMapper,
            OutboxEventMapper outboxEventMapper,
            TaskEventMapper taskEventMapper,
            QueueEstimateService queueEstimateService,
            ObjectMapper objectMapper,
            @Value("${loadflex.kafka.task-topic}") String taskTopic) {
        this.taskMapper = taskMapper;
        this.datasetMapper = datasetMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.taskEventMapper = taskEventMapper;
        this.queueEstimateService = queueEstimateService;
        this.objectMapper = objectMapper;
        this.taskTopic = taskTopic;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean dispatch(Long taskId) throws Exception {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null || !"QUEUED".equals(task.getStatus()) || Boolean.TRUE.equals(task.getCancelRequested())) {
            queueEstimateService.synchronizeAfterCommit(taskId);
            return false;
        }
        Dataset dataset = datasetMapper.selectById(task.getDatasetId());
        if (dataset == null) {
            throw new IllegalStateException("任务对应的数据集不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        task.setStatus("RUNNING");
        task.setStage("DISPATCHING");
        task.setProgress(BigDecimal.ZERO);
        task.setStartedAt(now);
        task.setQueuePosition(0);
        if (taskMapper.updateById(task) != 1) {
            return false;
        }

        String eventId = UUID.randomUUID().toString();
        TaskCommand command = new TaskCommand();
        command.setMessageId(eventId);
        command.setTaskId(task.getId());
        command.setDatasetId(task.getDatasetId());
        command.setTaskType(task.getTaskType());
        command.setObjectKey(dataset.getObjectKey());
        command.setMappingJson(dataset.getMappingJson());
        command.setParametersJson(task.getParametersJson());
        command.setAlgorithmVersion(task.getAlgorithmVersion());

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setAggregateType("ANALYSIS_TASK");
        outboxEvent.setAggregateId(task.getId().toString());
        outboxEvent.setEventType("task.execution.requested.v1");
        outboxEvent.setTopicName(taskTopic);
        outboxEvent.setMessageKey(task.getResourcePool());
        outboxEvent.setPayloadJson(objectMapper.writeValueAsString(command));
        outboxEvent.setStatus("NEW");
        outboxEvent.setRetryCount(0);
        outboxEventMapper.insert(outboxEvent);

        TaskEvent event = new TaskEvent();
        event.setTaskId(task.getId());
        event.setAttemptNo(task.getCurrentAttempt());
        event.setEventSeq(taskEventMapper.maxSeq(task.getId()) + 1);
        event.setEventType("DISPATCHED");
        event.setPayloadJson(objectMapper.writeValueAsString(Map.of("message", "调度器已分配计算资源，准备发送到 Kafka")));
        taskEventMapper.insert(event);
        queueEstimateService.synchronizeAfterCommit(task.getId());
        return true;
    }
}
