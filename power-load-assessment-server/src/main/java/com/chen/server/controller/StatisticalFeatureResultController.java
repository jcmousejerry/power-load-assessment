package com.chen.server.controller;

import com.chen.server.result.Result;
import com.chen.server.service.StatisticalFeatureResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计特征结果控制器
 */
@RestController
@RequestMapping("/api/statistical-feature-result")
public class StatisticalFeatureResultController {

    @Autowired
    private StatisticalFeatureResultService statisticalFeatureResultService;

    /**
     * 根据任务ID获取基本统计特征提取任务的结果
     *
     * @param taskId 任务ID
     * @return 统计特征结果
     */
    @GetMapping("/{taskId}")
    public Result getResultByTaskId(@PathVariable Long taskId) {
        return statisticalFeatureResultService.getResultByTaskId(taskId);
    }
}
