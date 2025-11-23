package com.chen.server.controller;

import com.chen.server.result.Result;
import com.chen.server.service.UserClusteringResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户聚类结果控制器
 */
@RestController
@RequestMapping("/api/user-clustering-result")
public class UserClusteringResultController {

    @Autowired
    private UserClusteringResultService userClusteringResultService;

    /**
     * 根据任务ID获取用户聚类任务的结果
     *
     * @param taskId 任务ID
     * @return 用户聚类结果列表
     */
    @GetMapping("/{taskId}")
    public Result getResultByTaskId(@PathVariable Long taskId) {
        return userClusteringResultService.getResultByTaskId(taskId);
    }
}
