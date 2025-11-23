package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.entity.StatisticalFeatureResult;
import com.chen.server.mapper.StatisticalFeatureResultMapper;
import com.chen.server.result.Result;
import com.chen.server.service.StatisticalFeatureResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 统计特征结果服务实现类
 */
@Service
public class StatisticalFeatureResultServiceImpl extends ServiceImpl<StatisticalFeatureResultMapper, StatisticalFeatureResult> implements StatisticalFeatureResultService {

    @Autowired
    private StatisticalFeatureResultMapper statisticalFeatureResultMapper;

    @Override
    public Result getResultByTaskId(Long taskId) {
        try {
            // 构造查询条件：根据 taskId 查询
            QueryWrapper<StatisticalFeatureResult> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("task_id", taskId);

            // 执行查询
            StatisticalFeatureResult result = statisticalFeatureResultMapper.selectOne(queryWrapper);

            // 判断结果是否存在
            if (result == null) {
                return Result.fail("未找到对应的任务执行结果");
            }

            // 返回成功结果
            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("查询任务执行结果失败: " + e.getMessage());
        }
    }
}
