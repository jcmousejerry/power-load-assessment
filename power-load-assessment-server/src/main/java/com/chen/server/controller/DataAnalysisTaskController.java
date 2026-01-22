package com.chen.server.controller;

import com.chen.server.dto.DataAnalysisTaskDTO;
import com.chen.server.result.Result;
import com.chen.server.service.DataAnalysisTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task")
public class DataAnalysisTaskController {

    @Autowired
    private DataAnalysisTaskService dataAnalysisTaskService;

    @PostMapping("/create")
    public Result createTask(@RequestBody DataAnalysisTaskDTO taskDTO) {
        return dataAnalysisTaskService.createTask(taskDTO);
    }

    /**
     * 获取当前用户的所有任务信息
     * @return 当前用户的所有任务列表
     */
    @GetMapping("/list")
    public Result getCurrentUserTasks() {
        return dataAnalysisTaskService.getCurrentUserTasks();
    }

    /**
     * 管理员获取平台全部用户的所有任务信息
     * @return 平台全部用户的所有任务列表
     */
    @GetMapping("/all")
    public Result getAllUsersTasks() {
        return dataAnalysisTaskService.getAllUsersTasks();
    }
}
