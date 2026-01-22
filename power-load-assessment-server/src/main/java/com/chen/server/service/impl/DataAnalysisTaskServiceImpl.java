package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.dto.DataAnalysisTaskDTO;
import com.chen.server.entity.DataAnalysisTask;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.DataAnalysisTaskMapper;
import com.chen.server.mapper.DatasetInfoMapper;
import com.chen.server.result.Result;
import com.chen.server.service.DataAnalysisTaskService;
import com.chen.server.utils.LoginUserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataAnalysisTaskServiceImpl extends ServiceImpl<DataAnalysisTaskMapper, DataAnalysisTask> implements DataAnalysisTaskService {

    @Autowired
    private DataAnalysisTaskMapper dataAnalysisTaskMapper;

    @Autowired
    private DatasetInfoMapper datasetInfoMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public Result createTask(DataAnalysisTaskDTO taskDTO) {
        try {
            // 创建任务实体
            DataAnalysisTask task = new DataAnalysisTask();
            BeanUtils.copyProperties(taskDTO, task);

            // 设置当前登录用户的ID
            Long userId = LoginUserHolder.getUserId();
            if (userId == null) {
                return Result.fail("用户未登录");
            }
            task.setUserId(userId);

            // 设置任务状态为未完成
            task.setStatus(0);

            // 保存任务
            dataAnalysisTaskMapper.insert(task);

            // 准备发送到Kafka的消息
            Map<String, Object> message = new HashMap<>();
            message.put("taskId", task.getId());
            message.put("datasetId", task.getDatasetId());
            message.put("taskType", task.getTaskType());
            message.put("clusterCount", taskDTO.getClusterCount());
            message.put("forecastSteps", taskDTO.getForecastSteps());
            message.put("status", 0); // 初始状态

            // 发送到Kafka
            kafkaTemplate.send("data-analysis-task-topic", message);

            return Result.ok("任务创建成功");
        } catch (Exception e) {
            return Result.fail("任务创建失败: " + e.getMessage());
        }
    }

    @Override
    public Result getCurrentUserTasks() {
        try {
            // 获取当前登录用户ID
            Long userId = LoginUserHolder.getUserId();
            if (userId == null) {
                return Result.fail("用户未登录");
            }

            // 构造查询条件：根据 userId 查询
            QueryWrapper<DataAnalysisTask> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId);
            queryWrapper.orderByDesc("create_time");

            // 执行查询
            List<DataAnalysisTask> tasks = dataAnalysisTaskMapper.selectList(queryWrapper);

            // 返回成功结果
            return Result.ok(tasks);
        } catch (Exception e) {
            return Result.fail("查询任务列表失败: " + e.getMessage());
        }
    }

    @Override
    public Result getAllUsersTasks() {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 检查用户是否为管理员
            if (!UserType.ADMIN.getCode().equals(currentUser.getUserType())) {
                return Result.fail("权限不足，只有管理员可以查看全部任务信息");
            }

            // 构造查询条件：查询所有任务
            QueryWrapper<DataAnalysisTask> queryWrapper = new QueryWrapper<>();
            queryWrapper.orderByDesc("create_time");

            // 执行查询
            List<DataAnalysisTask> tasks = dataAnalysisTaskMapper.selectList(queryWrapper);

            // 返回成功结果
            return Result.ok(tasks);
        } catch (Exception e) {
            return Result.fail("查询全部任务列表失败: " + e.getMessage());
        }
    }
}
