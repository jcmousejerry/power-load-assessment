package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chen.server.entity.DataAnalysisTask;
import com.chen.server.entity.LoadForecastResult;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.DataAnalysisTaskMapper;
import com.chen.server.mapper.LoadForecastResultMapper;
import com.chen.server.result.Result;
import com.chen.server.service.LoadForecastResultService;
import com.chen.server.utils.LoginUserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class LoadForecastResultServiceImpl implements LoadForecastResultService {

    @Autowired
    private LoadForecastResultMapper loadForecastResultMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DataAnalysisTaskMapper dataAnalysisTaskMapper;

    private static final String CACHE_KEY_PREFIX = "load_forecast_result:";

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
            List<LoadForecastResult> cachedResults = (List<LoadForecastResult>) redisTemplate.opsForValue().get(cacheKey);
            if (cachedResults != null && !cachedResults.isEmpty()) {
                return Result.ok(cachedResults);
            }

            // 缓存未命中，从数据库查询
            QueryWrapper<LoadForecastResult> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("task_id", taskId);
            List<LoadForecastResult> results = loadForecastResultMapper.selectList(queryWrapper);

            if (results == null || results.isEmpty()) {
                return Result.fail("未找到对应的任务执行结果");
            }

            // 写入缓存（设置过期时间为1小时）
            redisTemplate.opsForValue().set(cacheKey, results, 1, TimeUnit.HOURS);

            return Result.ok(results);
        } catch (Exception e) {
            return Result.fail("查询任务执行结果失败: " + e.getMessage());
        }
    }


    // 清除缓存的方法
    public void clearCacheByTaskId(Long taskId) {
        String cacheKey = CACHE_KEY_PREFIX + taskId;
        redisTemplate.delete(cacheKey);
    }
}
