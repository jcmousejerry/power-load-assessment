package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chen.server.entity.DataAnalysisTask;
import com.chen.server.entity.StatisticalFeatureResult;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.DataAnalysisTaskMapper;
import com.chen.server.mapper.StatisticalFeatureResultMapper;
import com.chen.server.result.Result;
import com.chen.server.service.StatisticalFeatureResultService;
import com.chen.server.utils.LoginUserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class StatisticalFeatureResultServiceImpl implements StatisticalFeatureResultService {

    @Autowired
    private StatisticalFeatureResultMapper statisticalFeatureResultMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DataAnalysisTaskMapper dataAnalysisTaskMapper;

    private static final String CACHE_KEY_PREFIX = "statistical_feature_result:";

    @Override
    public Result getResultByTaskId(Long taskId) {
        try {
            // 获取当前登录用户信息
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 如果不是管理员，则校验任务是否属于当前用户
            if (!UserType.ADMIN.getCode().equals(currentUser.getUserType())) {
                DataAnalysisTask task = dataAnalysisTaskMapper.selectById(taskId);
                if (task == null) {
                    return Result.fail("未找到对应的任务");
                }
                if (!task.getUserId().equals(currentUser.getId())) {
                    return Result.fail("权限不足，无法查看其他用户的任务结果");
                }
            }

            String cacheKey = CACHE_KEY_PREFIX + taskId;

            // 尝试从缓存中获取数据
            StatisticalFeatureResult cachedResult = (StatisticalFeatureResult) redisTemplate.opsForValue().get(cacheKey);
            if (cachedResult != null) {
                return Result.ok(cachedResult);
            }

            // 缓存未命中，从数据库查询
            QueryWrapper<StatisticalFeatureResult> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("task_id", taskId);
            StatisticalFeatureResult result = statisticalFeatureResultMapper.selectOne(queryWrapper);

            if (result == null) {
                return Result.fail("未找到对应的任务执行结果");
            }

            // 写入缓存（设置过期时间为1小时）
            redisTemplate.opsForValue().set(cacheKey, result, 1, TimeUnit.HOURS);

            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("查询任务执行结果失败: " + e.getMessage());
        }
    }

    // 清除缓存的方法（可被其他业务调用）
    public void clearCacheByTaskId(Long taskId) {
        String cacheKey = CACHE_KEY_PREFIX + taskId;
        redisTemplate.delete(cacheKey);
    }
}
