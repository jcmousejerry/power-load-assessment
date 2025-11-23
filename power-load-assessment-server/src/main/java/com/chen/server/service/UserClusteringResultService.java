package com.chen.server.service;

import com.chen.server.result.Result;

/**
 * 用户聚类结果服务接口
 */
public interface UserClusteringResultService {

    /**
     * 根据任务ID获取用户聚类任务的结果
     *
     * @param taskId 任务ID
     * @return 包含用户聚类结果列表的 Result 对象
     */
    Result getResultByTaskId(Long taskId);
}
