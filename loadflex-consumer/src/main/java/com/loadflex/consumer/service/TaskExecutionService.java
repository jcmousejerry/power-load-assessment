package com.loadflex.consumer.service;

import com.loadflex.common.messaging.TaskCommand;

public interface TaskExecutionService {
    void execute(TaskCommand taskCommand);
}
