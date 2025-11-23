package com.chen.server.service;

import com.chen.server.entity.StatisticalFeatureResult;
import com.chen.server.result.Result;

/**
 * 统计特征结果服务接口
 */
public interface StatisticalFeatureResultService {

    /**
     * 根据任务ID获取基本统计特征提取任务的结果
     *
     * @param taskId 任务ID
     * @return 包含统计特征结果的 Result 对象
     */
    Result getResultByTaskId(Long taskId);
}
