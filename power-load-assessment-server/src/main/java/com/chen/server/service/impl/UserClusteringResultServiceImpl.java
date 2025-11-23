package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.entity.UserClusteringResult;
import com.chen.server.mapper.UserClusteringResultMapper;
import com.chen.server.result.Result;
import com.chen.server.service.UserClusteringResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户聚类结果服务实现类
 */
@Service
public class UserClusteringResultServiceImpl extends ServiceImpl<UserClusteringResultMapper, UserClusteringResult> implements UserClusteringResultService {

    @Autowired
    private UserClusteringResultMapper userClusteringResultMapper;

    @Override
    public Result getResultByTaskId(Long taskId) {
        try {
            // 构造查询条件：根据 taskId 查询
            QueryWrapper<UserClusteringResult> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("task_id", taskId);

            // 执行查询
            List<UserClusteringResult> results = userClusteringResultMapper.selectList(queryWrapper);

            // 判断结果是否存在
            if (results == null || results.isEmpty()) {
                return Result.fail("未找到对应的任务执行结果");
            }

            // 返回成功结果
            return Result.ok(results);
        } catch (Exception e) {
            return Result.fail("查询任务执行结果失败: " + e.getMessage());
        }
    }
}
