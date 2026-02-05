package com.chen.server.controller;

import com.chen.server.dto.DataAnalysisTaskDTO;
import com.chen.server.result.Result;
import com.chen.server.service.DataAnalysisTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 管理员根据任务id删除指定任务的相关数据和模型
     * @param id 任务ID
     * @return 删除结果
     */
    @DeleteMapping("/delete/{id}")
    public Result deleteTaskById(@PathVariable Long id) {
        return dataAnalysisTaskService.deleteTaskById(id);
    }

}
