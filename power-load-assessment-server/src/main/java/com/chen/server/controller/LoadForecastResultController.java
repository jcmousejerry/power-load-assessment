package com.chen.server.controller;

import com.chen.server.result.Result;
import com.chen.server.service.LoadForecastResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 负荷预测结果控制器
 */
@RestController
@RequestMapping("/api/load-forecast-result")
public class LoadForecastResultController {

    @Autowired
    private LoadForecastResultService loadForecastResultService;

    /**
     * 根据任务ID获取负荷预测任务的结果
     *
     * @param taskId 任务ID
     * @return 负荷预测结果列表
     */
    @GetMapping("/{taskId}")
    public Result getResultByTaskId(@PathVariable Long taskId) {
        return loadForecastResultService.getResultByTaskId(taskId);
    }
}
