package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.entity.DataAnalysisTask;
import com.chen.server.entity.LoadForecastModel;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.DataAnalysisTaskMapper;
import com.chen.server.mapper.LoadForecastModelMapper;
import com.chen.server.result.Result;
import com.chen.server.service.ModelService;
import com.chen.server.utils.LoginUserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ModelServiceImpl extends ServiceImpl<LoadForecastModelMapper, LoadForecastModel> implements ModelService {

    @Autowired
    private LoadForecastModelMapper loadForecastModelMapper;

    @Autowired
    private DataAnalysisTaskMapper dataAnalysisTaskMapper;

    @Override
    public Result getCurrentUserModels() {
        try {
            // 获取当前登录用户ID
            Long userId = LoginUserHolder.getUserId();
            if (userId == null) {
                return Result.fail("用户未登录");
            }

            // 先查询当前用户创建的所有任务
            QueryWrapper<DataAnalysisTask> taskQueryWrapper = new QueryWrapper<>();
            taskQueryWrapper.eq("user_id", userId);
            List<DataAnalysisTask> tasks = dataAnalysisTaskMapper.selectList(taskQueryWrapper);

            // 如果没有任务，直接返回空列表
            if (tasks.isEmpty()) {
                return Result.ok("当前用户无模型信息");
            }

            // 提取任务ID列表
            List<Long> taskIds = tasks.stream()
                    .map(DataAnalysisTask::getId)
                    .collect(Collectors.toList());

            // 构造查询条件：根据 taskId 列表查询模型
            QueryWrapper<LoadForecastModel> modelQueryWrapper = new QueryWrapper<>();
            modelQueryWrapper.in("task_id", taskIds);
            List<LoadForecastModel> models = loadForecastModelMapper.selectList(modelQueryWrapper);

            return Result.ok(models, (long) models.size());
        } catch (Exception e) {
            return Result.fail("查询模型信息失败: " + e.getMessage());
        }
    }

    @Override
    public Result getAllUsersModels() {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 检查用户是否为管理员
            if (!UserType.ADMIN.getCode().equals(currentUser.getUserType())) {
                return Result.fail("权限不足，只有管理员可以查看全部模型信息");
            }

            // 构造查询条件：查询所有模型
            QueryWrapper<LoadForecastModel> queryWrapper = new QueryWrapper<>();
            queryWrapper.orderByDesc("create_time");

            // 执行查询
            List<LoadForecastModel> models = loadForecastModelMapper.selectList(queryWrapper);

            // 返回成功结果
            return Result.ok(models);
        } catch (Exception e) {
            return Result.fail("查询全部模型列表失败: " + e.getMessage());
        }
    }
}
