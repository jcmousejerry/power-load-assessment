package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.config.MinioConfig;
import com.chen.server.dto.DataAnalysisTaskDTO;
import com.chen.server.entity.*;
import com.chen.server.enums.TaskStatus;
import com.chen.server.enums.TaskType;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.*;
import com.chen.server.result.Result;
import com.chen.server.service.DataAnalysisTaskService;
import com.chen.server.utils.LoginUserHolder;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
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

    @Autowired
    private StatisticalFeatureResultMapper statisticalFeatureResultMapper;

    @Autowired
    private UserClusteringResultMapper userClusteringResultMapper;

    @Autowired
    private LoadForecastModelMapper loadForecastModelMapper;

    @Autowired
    private LoadForecastResultMapper loadForecastResultMapper;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioConfig minioConfig;

    @Override
    public Result createTask(DataAnalysisTaskDTO taskDTO) {
        try {
            // 创建任务实体
            DataAnalysisTask task = new DataAnalysisTask();
            BeanUtils.copyProperties(taskDTO, task);

            // 设置用户ID
            Long userId = LoginUserHolder.getUserId();
            task.setUserId(userId);

            // 设置任务状态为未完成
            task.setStatus(TaskStatus.PENDING.getCode());

            // 保存到数据库
            dataAnalysisTaskMapper.insert(task);

            // 查询数据集名称
            DatasetInfo datasetInfo = datasetInfoMapper.selectById(taskDTO.getDatasetId());
            String datasetPath = datasetInfo != null ? datasetInfo.getPath() : "";

            // 构造包含数据集名称的消息
            Map<String, Object> message = new HashMap<>();
            message.put("task", task);
            message.put("datasetPath", datasetPath);
            // 添加clusterCount和forecastSteps到消息中
            message.put("clusterCount", taskDTO.getClusterCount());
            message.put("forecastSteps", taskDTO.getForecastSteps());
            // 添加用户ID用于通知
            message.put("userId", userId);

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

    @Override
    public Result deleteTaskById(Long id) {
        try {
            // 获取当前登录用户
            User currentUser = LoginUserHolder.getUser();
            if (currentUser == null) {
                return Result.fail("用户未登录");
            }

            // 检查用户是否为管理员
            if (!UserType.ADMIN.getCode().equals(currentUser.getUserType())) {
                return Result.fail("权限不足，只有管理员可以删除任务");
            }

            // 根据ID查询任务信息
            DataAnalysisTask task = dataAnalysisTaskMapper.selectById(id);
            if (task == null) {
                return Result.fail("未找到指定的任务");
            }

            // 检查任务是否已完成，如果未完成则等待
            if (TaskStatus.PENDING.getCode().equals(task.getStatus())) {
                // 等待任务完成的最大时间（毫秒）
                long maxWaitTime = 300000; // 5分钟
                long startTime = System.currentTimeMillis();

                // 循环检查任务状态直到完成或超时
                while (TaskStatus.PENDING.getCode().equals(task.getStatus())
                        && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                    try {
                        // 等待一段时间再重新检查
                        Thread.sleep(5000); // 等待5秒
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Result.fail("等待任务完成过程中被中断");
                    }

                    // 重新查询任务状态
                    task = dataAnalysisTaskMapper.selectById(id);
                }

                // 检查是否超时
                if (TaskStatus.PENDING.getCode().equals(task.getStatus())) {
                    return Result.fail("任务等待超时，未能完成");
                }
            }

            // 根据任务类型处理不同情况
            Integer taskType = task.getTaskType();

            if (TaskType.STATISTICAL_FEATURE_EXTRACTION.getCode().equals(taskType)) {
                // 基本统计特征提取任务：删除统计特征结果
                QueryWrapper<StatisticalFeatureResult> statisticalQueryWrapper = new QueryWrapper<>();
                statisticalQueryWrapper.eq("task_id", id);
                statisticalFeatureResultMapper.delete(statisticalQueryWrapper);

            } else if (TaskType.USER_CLUSTERING.getCode().equals(taskType)) {
                // 用户聚类任务：删除聚类结果
                QueryWrapper<UserClusteringResult> clusteringQueryWrapper = new QueryWrapper<>();
                clusteringQueryWrapper.eq("task_id", id);
                userClusteringResultMapper.delete(clusteringQueryWrapper);

            } else if (TaskType.LOAD_FORECASTING.getCode().equals(taskType)) {
                // 负荷预测任务：删除模型和结果
                // 查询模型信息
                QueryWrapper<LoadForecastModel> modelQueryWrapper = new QueryWrapper<>();
                modelQueryWrapper.eq("task_id", id);
                List<LoadForecastModel> models = loadForecastModelMapper.selectList(modelQueryWrapper);

                // 删除MinIO中的模型文件
                for (LoadForecastModel model : models) {
                    try {
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(minioConfig.getBucketName())
                                        .object(model.getModelPath())
                                        .build()
                        );
                    } catch (Exception e) {
                        // 记录错误但继续删除其他文件
                        System.err.println("删除MinIO文件失败: " + e.getMessage());
                    }
                }

                // 删除模型信息
                loadForecastModelMapper.delete(modelQueryWrapper);

                // 删除预测结果
                QueryWrapper<LoadForecastResult> forecastResultQueryWrapper = new QueryWrapper<>();
                forecastResultQueryWrapper.eq("task_id", id);
                loadForecastResultMapper.delete(forecastResultQueryWrapper);
            }

            // 最后删除任务本身
            dataAnalysisTaskMapper.deleteById(id);

            return Result.ok("任务删除成功");
        } catch (Exception e) {
            return Result.fail("删除任务失败: " + e.getMessage());
        }
    }

}
