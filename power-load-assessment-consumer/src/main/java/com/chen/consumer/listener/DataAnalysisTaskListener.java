package com.chen.consumer.listener;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.chen.consumer.entity.DataAnalysisTask;
import com.chen.consumer.enums.TaskStatus;
import com.chen.consumer.mapper.DataAnalysisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Component
public class DataAnalysisTaskListener {

    private static final Logger logger = LoggerFactory.getLogger(DataAnalysisTaskListener.class);

    @Autowired
    private DataAnalysisTaskMapper taskMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // Python脚本路径
    private static final String PYTHON_SCRIPT_PATH = "D:/ONLY_ENGLISH_DIR/projects/python-scripts-202510/power-load-assessment-scripts/task2.py";

    // 指定Anaconda虚拟环境中的Python解释器路径
    private static final String PYTHON_EXECUTABLE_PATH = "D:/anaconda/envs/self_env_2/python.exe";

    @KafkaListener(topics = "data-analysis-task-topic", groupId = "data-analysis-task-group")
    public void handleDataAnalysisTask(Map<String, Object> message) {
        try {
            // 从消息中提取任务和数据集路径
            Object taskObj = message.get("task");
            String datasetPath = (String) message.get("datasetPath");
            // 提取clusterCount和forecastSteps参数
            Integer clusterCount = (Integer) message.get("clusterCount");
            Integer forecastSteps = (Integer) message.get("forecastSteps");

            // 安全地提取 userId
            Object userIdObj = message.get("userId");
            Long userId;
            if (userIdObj instanceof Integer) {
                userId = ((Integer) userIdObj).longValue();
            } else if (userIdObj instanceof Long) {
                userId = (Long) userIdObj;
            } else {
                throw new IllegalArgumentException("userId 必须是 Integer 或 Long 类型");
            }

            // 安全地将 LinkedHashMap 转换为 DataAnalysisTask 对象
            DataAnalysisTask task = convertToDataAnalysisTask(taskObj);

            logger.info("接收到数据分析任务: ID={}, Type={}, datasetPath={}", task.getId(), task.getTaskType(), datasetPath);

            // 执行Python脚本
            executePythonScript(task, datasetPath, clusterCount, forecastSteps);

            // 更新任务状态为已完成
            updateTaskStatus(task.getId(), TaskStatus.COMPLETED.getCode());

            // 发送任务完成通知到服务器端
            sendTaskCompletionNotification(task.getId(), task.getTaskType(), userId);

            logger.info("数据分析任务处理完成: ID={}", task.getId());
        } catch (Exception e) {
            logger.error("处理数据分析任务失败", e);

            // 获取任务对象以获取用户ID
            Object taskObj = message.get("task");
            DataAnalysisTask task = convertToDataAnalysisTask(taskObj);

            // 发送任务失败通知
            sendTaskFailureNotification(task.getId(), task.getTaskType(), task.getUserId(), e.getMessage());
        }
    }


    /**
     * 执行Python脚本
     * @param task 任务对象
     * @param datasetPath 数据集路径
     * @param clusterCount 聚类数量
     * @param forecastSteps 预测步数
     */
    private void executePythonScript(DataAnalysisTask task, String datasetPath, Integer clusterCount, Integer forecastSteps) throws Exception {
        // 构建命令行参数
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(PYTHON_EXECUTABLE_PATH, PYTHON_SCRIPT_PATH,
                "--taskId", String.valueOf(task.getId()),
                "--taskType", String.valueOf(task.getTaskType()),
                "--datasetId", String.valueOf(task.getDatasetId()),
                "--datasetPath", datasetPath);

        processBuilder.command().addAll(Arrays.asList("--clusterCount", String.valueOf(clusterCount)));
        processBuilder.command().addAll(Arrays.asList("--forecastSteps", String.valueOf(forecastSteps)));

        // 启动进程
        Process process = processBuilder.start();

        // 读取输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            logger.info("Python脚本输出: {}", line);
        }

        // 读取错误输出
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String errorLine;
        while ((errorLine = errorReader.readLine()) != null) {
            logger.error("Python脚本错误输出: {}", errorLine);
        }

        // 等待执行完成
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Python脚本执行失败，退出码: " + exitCode);
        }
    }

    /**
     * 将 Object（通常是 LinkedHashMap）转换为 DataAnalysisTask 对象
     * @param obj 从 Kafka 消息中获取的对象
     * @return DataAnalysisTask 实例
     */
    @SuppressWarnings("unchecked")
    private DataAnalysisTask convertToDataAnalysisTask(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            DataAnalysisTask task = new DataAnalysisTask();

            // 手动设置各个字段
            task.setId(((Number) map.get("id")).longValue());
            task.setUserId(((Number) map.get("userId")).longValue());
            task.setDatasetId(((Number) map.get("datasetId")).longValue());
            task.setTaskType((Integer) map.get("taskType"));
            task.setStatus((Integer) map.get("status"));

            return task;
        } else if (obj instanceof DataAnalysisTask) {
            return (DataAnalysisTask) obj;
        }

        throw new IllegalArgumentException("无法将对象转换为 DataAnalysisTask: " + obj.getClass());
    }

    /**
     * 更新任务状态
     * @param taskId 任务ID
     * @param status 新状态
     */
    private void updateTaskStatus(Long taskId, Integer status) {
        UpdateWrapper<DataAnalysisTask> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", taskId).set("status", status);
        taskMapper.update(null, updateWrapper);
    }

    /**
     * 发送任务完成通知到服务器端
     */
    private void sendTaskCompletionNotification(Long taskId, Integer taskType, Long userId) {
        // 发送消息到服务器端的通知主题
        Map<String, Object> notificationMessage = new java.util.HashMap<>();
        notificationMessage.put("taskId", taskId);
        notificationMessage.put("taskType", taskType);
        notificationMessage.put("userId", userId);
        notificationMessage.put("status", TaskStatus.COMPLETED.getCode());
        notificationMessage.put("message", "任务已完成");

        kafkaTemplate.send("task-completion-notification", notificationMessage);
    }

    /**
     * 发送任务失败通知到服务器端
     */
    private void sendTaskFailureNotification(Long taskId, Integer taskType, Long userId, String errorMessage) {
        // 发送消息到服务器端的通知主题
        Map<String, Object> notificationMessage = new HashMap<>();
        notificationMessage.put("taskId", taskId);
        notificationMessage.put("taskType", taskType);
        notificationMessage.put("userId", userId);
        notificationMessage.put("status", 0); // 失败状态
        notificationMessage.put("message", "任务执行失败: " + errorMessage);

        kafkaTemplate.send("task-completion-notification", notificationMessage);
    }
}
