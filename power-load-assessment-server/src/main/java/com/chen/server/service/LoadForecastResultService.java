package com.chen.server.service;

import com.chen.server.result.Result;

/**
 * 负荷预测结果服务接口
 */
public interface LoadForecastResultService {

    /**
     * 根据任务ID获取负荷预测任务的结果
     *
     * @param taskId 任务ID
     * @return 包含负荷预测结果列表的 Result 对象
     */
    Result getResultByTaskId(Long taskId);
}
