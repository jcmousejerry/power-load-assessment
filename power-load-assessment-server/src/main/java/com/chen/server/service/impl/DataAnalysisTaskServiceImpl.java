package com.chen.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.dto.DataAnalysisTaskDTO;
import com.chen.server.entity.DataAnalysisTask;
import com.chen.server.entity.DatasetInfo;
import com.chen.server.enums.TaskStatus;
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

            // 设置用户ID
            task.setUserId(LoginUserHolder.getUserId());

            // 设置任务状态为未完成
            task.setStatus(TaskStatus.PENDING.getCode());

            System.out.println(task.getId());

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

            // 发送到Kafka
            kafkaTemplate.send("data-analysis-task-topic", message);

            return Result.ok("任务创建成功");
        } catch (Exception e) {
            return Result.fail("任务创建失败: " + e.getMessage());
        }
    }

}
