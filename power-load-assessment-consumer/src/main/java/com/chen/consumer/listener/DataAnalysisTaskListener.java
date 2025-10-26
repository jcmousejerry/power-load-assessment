package com.chen.consumer.listener;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.chen.consumer.entity.DataAnalysisTask;
import com.chen.consumer.enums.TaskStatus;
import com.chen.consumer.mapper.DataAnalysisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Component
public class DataAnalysisTaskListener {

    private static final Logger logger = LoggerFactory.getLogger(DataAnalysisTaskListener.class);

    @Autowired
    private DataAnalysisTaskMapper taskMapper;

    // Python脚本路径 - 根据实际情况调整
    private static final String PYTHON_SCRIPT_PATH = "/path/to/your/python/script.py";

    @KafkaListener(topics = "data-analysis-task-topic", groupId = "data-analysis-task-group")
    public void handleDataAnalysisTask(DataAnalysisTask task) {
        try {
            logger.info("接收到数据分析任务: ID={}, Type={}", task.getId(), task.getTaskType());

            // 执行Python脚本
            executePythonScript(task);

            // 更新任务状态为已完成
            updateTaskStatus(task.getId(), TaskStatus.COMPLETED.getCode());

            logger.info("数据分析任务处理完成: ID={}", task.getId());
        } catch (Exception e) {
            logger.error("处理数据分析任务失败: ID=" + task.getId(), e);
        }
    }

    /**
     * 执行Python脚本
     * @param task 任务对象
     */
    private void executePythonScript(DataAnalysisTask task) throws Exception {
        // 构建命令行参数
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("python", PYTHON_SCRIPT_PATH,
                              "--taskId", String.valueOf(task.getId()),
                              "--taskType", String.valueOf(task.getTaskType()),
                              "--datasetId", String.valueOf(task.getDatasetId()));

        // 启动进程
        Process process = processBuilder.start();

        // 读取输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            logger.info("Python脚本输出: {}", line);
        }

        // 等待执行完成
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Python脚本执行失败，退出码: " + exitCode);
        }
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
}
