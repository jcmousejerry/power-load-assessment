package com.chen.server.service;

import com.chen.server.dto.DataAnalysisTaskDTO;
import com.chen.server.result.Result;

public interface DataAnalysisTaskService {

    Result createTask(DataAnalysisTaskDTO taskDTO);

    Result getCurrentUserTasks();
}
