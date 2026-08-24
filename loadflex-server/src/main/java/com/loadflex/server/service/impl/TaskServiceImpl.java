package com.loadflex.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.AnalysisPipeline;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.Dataset;
import com.loadflex.common.entity.TaskDependency;
import com.loadflex.common.entity.TaskEvent;
import com.loadflex.common.mapper.AnalysisPipelineMapper;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.common.mapper.TaskDependencyMapper;
import com.loadflex.common.mapper.TaskEventMapper;
import com.loadflex.server.security.AccessControlService;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import com.loadflex.server.service.DatasetService;
import com.loadflex.server.service.QueueEstimateService;
import com.loadflex.server.service.TaskService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskServiceImpl implements TaskService {

    private static final Set<String> SUPPORTED_TASK_TYPES =
            Set.of("PROFILE", "FEATURE", "CLUSTER", "FORECAST", "BASELINE", "POTENTIAL");

    private static final Set<String> FINISHED_TASK_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED");

    private final AnalysisTaskMapper taskMapper;
    private final AnalysisPipelineMapper pipelineMapper;
    private final TaskEventMapper taskEventMapper;
    private final TaskDependencyMapper taskDependencyMapper;
    private final DatasetService datasetService;
    private final AccessControlService accessControlService;
    private final QueueEstimateService queueEstimateService;
    private final ObjectMapper objectMapper;

    public TaskServiceImpl(
            AnalysisTaskMapper taskMapper,
            AnalysisPipelineMapper pipelineMapper,
            TaskEventMapper taskEventMapper,
            TaskDependencyMapper taskDependencyMapper,
            DatasetService datasetService,
            AccessControlService accessControlService,
            QueueEstimateService queueEstimateService,
            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.pipelineMapper = pipelineMapper;
        this.taskEventMapper = taskEventMapper;
        this.taskDependencyMapper = taskDependencyMapper;
        this.datasetService = datasetService;
        this.accessControlService = accessControlService;
        this.queueEstimateService = queueEstimateService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnalysisTask create(
            String type, String name, Long datasetId, Map<String, Object> parameters, String idempotencyKey)
            throws Exception {
        accessControlService.requireAnalysisPermission();
        LoginUser loginUser = UserContext.get();
        datasetService.accessible(datasetId);
        String taskType = normalizeTaskType(type);
        validateManualPrerequisites(datasetId, taskType);

        AnalysisTask existingTask = findByIdempotencyKey(loginUser.getUserId(), idempotencyKey);
        if (existingTask != null) {
            return existingTask;
        }

        AnalysisTask task = buildTask(loginUser, datasetId, null, taskType, name, parameters, idempotencyKey, false);
        taskMapper.insert(task);
        addTaskEvent(task, "QUEUED", Map.of("queuePosition", task.getQueuePosition(), "message", "任务已进入等待队列"));
        queueEstimateService.synchronizeAfterCommit(task.getId());
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AnalysisTask> createPipeline(
            Long datasetId,
            String pipelineName,
            Integer requestedClusterCount,
            Integer requestedForecastSteps,
            String requestedBaselineType,
            Double maximumAdjustableKw)
            throws Exception {
        accessControlService.requireAnalysisPermission();
        LoginUser loginUser = UserContext.get();
        Dataset dataset = datasetService.accessible(datasetId);
        int clusterCount = requestedClusterCount == null ? 3 : Math.max(2, Math.min(6, requestedClusterCount));
        int forecastSteps = requestedForecastSteps == null ? 96 : Math.max(1, Math.min(192, requestedForecastSteps));
        String baselineType =
                requestedBaselineType == null || requestedBaselineType.isBlank() ? "typical" : requestedBaselineType;
        String prefix = pipelineName == null || pipelineName.isBlank() ? dataset.getName() : pipelineName.trim();
        AnalysisPipeline pipeline = new AnalysisPipeline();
        pipeline.setUserId(loginUser.getUserId());
        pipeline.setDatasetId(datasetId);
        pipeline.setPipelineName(prefix);
        pipelineMapper.insert(pipeline);

        List<AnalysisTask> tasks = new ArrayList<>();
        AnalysisTask profile = createPipelineTask(
                loginUser, datasetId, pipeline.getId(), "PROFILE", prefix + " - 数据质量检查", Map.of(), false);
        tasks.add(profile);
        AnalysisTask feature = createPipelineTask(
                loginUser, datasetId, pipeline.getId(), "FEATURE", prefix + " - 用户特征提取", Map.of(), true);
        addDependency(feature, profile);
        tasks.add(feature);
        AnalysisTask cluster = createPipelineTask(
                loginUser,
                datasetId,
                pipeline.getId(),
                "CLUSTER",
                prefix + " - 用户聚类",
                Map.of("clusterCount", clusterCount),
                true);
        addDependency(cluster, feature);
        tasks.add(cluster);

        for (int clusterId = 1; clusterId <= clusterCount; clusterId++) {
            Map<String, Object> forecastParameters = new LinkedHashMap<>();
            forecastParameters.put("clusterId", clusterId);
            forecastParameters.put("forecastSteps", forecastSteps);
            AnalysisTask forecast = createPipelineTask(
                    loginUser,
                    datasetId,
                    pipeline.getId(),
                    "FORECAST",
                    prefix + " - 集群" + clusterId + "负荷预测",
                    forecastParameters,
                    true);
            addDependency(forecast, cluster);
            tasks.add(forecast);

            AnalysisTask baseline = createPipelineTask(
                    loginUser,
                    datasetId,
                    pipeline.getId(),
                    "BASELINE",
                    prefix + " - 集群" + clusterId + "基线",
                    Map.of("clusterId", clusterId),
                    true);
            addDependency(baseline, cluster);
            tasks.add(baseline);

            Map<String, Object> potentialParameters = new LinkedHashMap<>();
            potentialParameters.put("clusterId", clusterId);
            potentialParameters.put("forecastSteps", forecastSteps);
            potentialParameters.put("baselineType", baselineType);
            if (maximumAdjustableKw != null) {
                potentialParameters.put("maximumAdjustableKw", maximumAdjustableKw);
            }
            AnalysisTask potential = createPipelineTask(
                    loginUser,
                    datasetId,
                    pipeline.getId(),
                    "POTENTIAL",
                    prefix + " - 集群" + clusterId + "调节潜力",
                    potentialParameters,
                    true);
            addDependency(potential, forecast);
            addDependency(potential, baseline);
            tasks.add(potential);
        }

        addTaskEvent(profile, "QUEUED", Map.of("queuePosition", profile.getQueuePosition(), "message", "流水线首个任务已进入队列"));
        queueEstimateService.synchronizeAfterCommit(profile.getId());
        return tasks;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnalysisTask cancel(Long id) throws Exception {
        accessControlService.requireAnalysisPermission();
        AnalysisTask task = get(id);
        if (FINISHED_TASK_STATUSES.contains(task.getStatus())) {
            throw new IllegalArgumentException("任务已经结束，不能取消");
        }

        task.setCancelRequested(true);
        if ("QUEUED".equals(task.getStatus()) || "WAITING_DEPENDENCY".equals(task.getStatus())) {
            task.setStatus("CANCELLED");
            task.setStage("CANCELLED_BEFORE_START");
            task.setFinishedAt(LocalDateTime.now());
        } else {
            task.setStatus("CANCEL_REQUESTED");
        }

        taskMapper.updateById(task);
        addTaskEvent(task, "CANCEL_REQUESTED", Map.of("message", "用户请求取消任务"));
        queueEstimateService.synchronizeAfterCommit(task.getId());
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnalysisTask retry(Long id) throws Exception {
        accessControlService.requireAnalysisPermission();
        AnalysisTask task = get(id);
        if (!"FAILED".equals(task.getStatus())) {
            throw new IllegalArgumentException("只有执行失败的任务可以重试");
        }
        if (task.getCurrentAttempt() >= 3) {
            throw new IllegalArgumentException("任务已经执行3次，不能继续自动重试");
        }

        task.setStatus("QUEUED");
        task.setStage("WAITING_RESOURCE");
        task.setProgress(BigDecimal.ZERO);
        task.setCancelRequested(false);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setQueuedAt(LocalDateTime.now());
        applyInitialQueueInformation(task);
        taskMapper.updateById(task);

        addTaskEvent(task, "RETRY_QUEUED", Map.of("attempt", task.getCurrentAttempt() + 1));
        queueEstimateService.synchronizeAfterCommit(task.getId());
        return task;
    }

    @Override
    public List<AnalysisTask> list(Long datasetId, Long pipelineId) {
        LoginUser loginUser = UserContext.get();
        LambdaQueryWrapper<AnalysisTask> queryWrapper = new LambdaQueryWrapper<AnalysisTask>()
                .eq(datasetId != null, AnalysisTask::getDatasetId, datasetId)
                .eq(pipelineId != null && pipelineId > 0, AnalysisTask::getPipelineId, pipelineId)
                .isNull(pipelineId != null && pipelineId == 0, AnalysisTask::getPipelineId)
                .orderByDesc(AnalysisTask::getCreatedAt);
        if (!isAdmin(loginUser)) {
            queryWrapper.eq(AnalysisTask::getUserId, loginUser.getUserId());
        }
        List<AnalysisTask> tasks = taskMapper.selectList(queryWrapper);
        tasks.forEach(queueEstimateService::applyCurrentEstimate);
        return tasks;
    }

    @Override
    public AnalysisTask get(Long id) {
        AnalysisTask task = taskMapper.selectById(id);
        LoginUser loginUser = UserContext.get();
        if (task == null || (!isAdmin(loginUser) && !loginUser.getUserId().equals(task.getUserId()))) {
            throw new IllegalArgumentException("任务不存在或无权访问");
        }
        queueEstimateService.applyCurrentEstimate(task);
        return task;
    }

    @Override
    public List<TaskEvent> events(Long id) {
        get(id);
        return taskEventMapper.selectList(
                new LambdaQueryWrapper<TaskEvent>().eq(TaskEvent::getTaskId, id).orderByAsc(TaskEvent::getEventSeq));
    }

    private String normalizeTaskType(String type) {
        String taskType = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TASK_TYPES.contains(taskType)) {
            throw new IllegalArgumentException("不支持的任务类型");
        }
        return taskType;
    }

    private AnalysisTask findByIdempotencyKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return taskMapper.selectOne(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getUserId, userId)
                .eq(AnalysisTask::getIdempotencyKey, idempotencyKey));
    }

    private AnalysisTask buildTask(
            LoginUser loginUser,
            Long datasetId,
            Long pipelineId,
            String taskType,
            String name,
            Map<String, Object> parameters,
            String idempotencyKey,
            boolean waitingDependency)
            throws Exception {
        AnalysisTask task = new AnalysisTask();
        task.setUserId(loginUser.getUserId());
        task.setDatasetId(datasetId);
        task.setPipelineId(pipelineId);
        task.setTaskType(taskType);
        task.setTaskName(name == null || name.isBlank() ? taskType + "分析任务" : name.trim());
        task.setStatus(waitingDependency ? "WAITING_DEPENDENCY" : "QUEUED");
        task.setStage(waitingDependency ? "WAITING_PREREQUISITE" : "WAITING_RESOURCE");
        task.setProgress(BigDecimal.ZERO);
        task.setPriority(0);
        task.setResourcePool(isHighMemoryTask(taskType) ? "CPU_HIGH_MEM" : "CPU_LIGHT");
        task.setParametersJson(objectMapper.writeValueAsString(parameters == null ? Map.of() : parameters));
        task.setAlgorithmVersion("1.0.0");
        task.setQueuedAt(waitingDependency ? null : LocalDateTime.now());
        task.setIdempotencyKey(
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? UUID.randomUUID().toString()
                        : idempotencyKey);
        task.setCurrentAttempt(0);
        task.setCancelRequested(false);
        task.setVersion(0L);

        if (!waitingDependency) {
            applyInitialQueueInformation(task);
        }
        return task;
    }

    private AnalysisTask createPipelineTask(
            LoginUser loginUser,
            Long datasetId,
            Long pipelineId,
            String taskType,
            String taskName,
            Map<String, Object> parameters,
            boolean waitingDependency)
            throws Exception {
        AnalysisTask task = buildTask(
                loginUser,
                datasetId,
                pipelineId,
                taskType,
                taskName,
                parameters,
                UUID.randomUUID().toString(),
                waitingDependency);
        taskMapper.insert(task);
        if (waitingDependency) {
            addTaskEvent(task, "WAITING_DEPENDENCY", Map.of("message", "等待上游任务成功"));
        }
        return task;
    }

    private void addDependency(AnalysisTask child, AnalysisTask prerequisite) {
        TaskDependency dependency = new TaskDependency();
        dependency.setChildTaskId(child.getId());
        dependency.setPrerequisiteTaskId(prerequisite.getId());
        dependency.setCreatedAt(LocalDateTime.now());
        taskDependencyMapper.insert(dependency);
    }

    private void validateManualPrerequisites(Long datasetId, String taskType) {
        if ("PROFILE".equals(taskType)) {
            return;
        }
        String prerequisite;
        switch (taskType) {
            case "FEATURE":
                prerequisite = "PROFILE";
                break;
            case "CLUSTER":
                prerequisite = "FEATURE";
                break;
            case "FORECAST":
            case "BASELINE":
                prerequisite = "CLUSTER";
                break;
            case "POTENTIAL":
                long forecastCount = successfulTaskCount(datasetId, "FORECAST");
                long baselineCount = successfulTaskCount(datasetId, "BASELINE");
                if (forecastCount == 0 || baselineCount == 0) {
                    throw new IllegalArgumentException("调节潜力任务需要该数据集先完成预测和基线任务；建议创建完整分析流水线");
                }
                return;
            default:
                return;
        }
        if (successfulTaskCount(datasetId, prerequisite) == 0) {
            throw new IllegalArgumentException("该任务缺少已成功的上游任务 " + prerequisite + "；建议创建完整分析流水线");
        }
    }

    private long successfulTaskCount(Long datasetId, String taskType) {
        return taskMapper.selectCount(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getDatasetId, datasetId)
                .eq(AnalysisTask::getTaskType, taskType)
                .eq(AnalysisTask::getStatus, "SUCCEEDED"));
    }

    private void applyInitialQueueInformation(AnalysisTask task) {
        task.setQueuePosition(1);
        task.setQueuedAheadCount(0);
        task.setEstimatedWaitSeconds(0L);
        task.setEstimatedStartAt(LocalDateTime.now());
    }

    private void addTaskEvent(AnalysisTask task, String eventType, Object payload) throws Exception {
        TaskEvent taskEvent = new TaskEvent();
        taskEvent.setTaskId(task.getId());
        taskEvent.setAttemptNo(task.getCurrentAttempt());
        taskEvent.setEventSeq(taskEventMapper.maxSeq(task.getId()) + 1);
        taskEvent.setEventType(eventType);
        taskEvent.setPayloadJson(objectMapper.writeValueAsString(payload));
        taskEventMapper.insert(taskEvent);
    }

    private boolean isHighMemoryTask(String taskType) {
        return "CLUSTER".equals(taskType) || "FORECAST".equals(taskType);
    }

    private boolean isAdmin(LoginUser loginUser) {
        return "ADMIN".equals(loginUser.getRoleCode());
    }
}
