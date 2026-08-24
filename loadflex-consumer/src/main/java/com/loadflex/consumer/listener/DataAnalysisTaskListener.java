package com.loadflex.consumer.listener;

import com.loadflex.common.messaging.TaskCommand;
import com.loadflex.consumer.service.TaskExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DataAnalysisTaskListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataAnalysisTaskListener.class);

    private final TaskExecutionService taskExecutionService;

    public DataAnalysisTaskListener(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    @KafkaListener(topics = "${loadflex.kafka.task-topic}", groupId = "loadflex-algorithm-consumer")
    public void handleTask(TaskCommand taskCommand) {
        LOGGER.info(
                "收到分析任务，taskId={}, taskType={}, messageId={}",
                taskCommand.getTaskId(),
                taskCommand.getTaskType(),
                taskCommand.getMessageId());
        taskExecutionService.execute(taskCommand);
    }
}
