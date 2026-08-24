package com.loadflex.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.common.entity.AnalysisResult;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.mapper.AnalysisResultMapper;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import com.loadflex.server.service.ResultService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResultServiceImpl implements ResultService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultServiceImpl.class);
    private static final String FORECAST_CACHE_PREFIX = "loadflex:result:forecast:";

    private final AnalysisResultMapper resultMapper;
    private final AnalysisTaskMapper taskMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration cacheDuration;

    public ResultServiceImpl(
            AnalysisResultMapper resultMapper,
            AnalysisTaskMapper taskMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${loadflex.result-cache.ttl-seconds:600}") long cacheTtlSeconds) {
        this.resultMapper = resultMapper;
        this.taskMapper = taskMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheDuration = Duration.ofSeconds(cacheTtlSeconds);
    }

    @Override
    public List<AnalysisResult> list(Long datasetId, Long pipelineId) {
        LoginUser loginUser = UserContext.get();
        LambdaQueryWrapper<AnalysisTask> taskQuery = new LambdaQueryWrapper<AnalysisTask>()
                .eq(datasetId != null, AnalysisTask::getDatasetId, datasetId)
                .eq(pipelineId != null && pipelineId > 0, AnalysisTask::getPipelineId, pipelineId)
                .isNull(pipelineId != null && pipelineId == 0, AnalysisTask::getPipelineId);
        if (!isAdmin(loginUser)) {
            taskQuery.eq(AnalysisTask::getUserId, loginUser.getUserId());
        }
        List<Long> taskIds = taskMapper.selectList(taskQuery).stream()
                .map(AnalysisTask::getId)
                .toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return resultMapper.selectList(new LambdaQueryWrapper<AnalysisResult>()
                .in(AnalysisResult::getTaskId, taskIds)
                .orderByDesc(AnalysisResult::getCreatedAt));
    }

    @Override
    public AnalysisResult get(Long id) {
        AnalysisResult cachedResult = readForecastCache(id);
        if (cachedResult != null) {
            verifyAccess(cachedResult);
            return cachedResult;
        }

        AnalysisResult result = resultMapper.selectById(id);
        if (result == null) {
            throw new IllegalArgumentException("结果不存在");
        }

        verifyAccess(result);
        writeForecastCache(result);
        return result;
    }

    private void verifyAccess(AnalysisResult result) {
        AnalysisTask task = taskMapper.selectById(result.getTaskId());
        LoginUser loginUser = UserContext.get();
        if (task == null || (!isAdmin(loginUser) && !loginUser.getUserId().equals(task.getUserId()))) {
            throw new IllegalArgumentException("结果不存在或无权访问");
        }
    }

    private AnalysisResult readForecastCache(Long resultId) {
        String cacheKey = FORECAST_CACHE_PREFIX + resultId;
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson == null || cachedJson.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(cachedJson, AnalysisResult.class);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("预测结果缓存内容无法解析，删除缓存，resultId={}", resultId, exception);
            redisTemplate.delete(cacheKey);
            return null;
        }
    }

    private void writeForecastCache(AnalysisResult result) {
        if (!"FORECAST".equals(result.getResultType())) {
            return;
        }

        try {
            String cacheKey = FORECAST_CACHE_PREFIX + result.getId();
            String resultJson = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, resultJson, cacheDuration);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("预测结果写入 Redis 失败，resultId={}", result.getId(), exception);
        }
    }

    private boolean isAdmin(LoginUser loginUser) {
        return "ADMIN".equals(loginUser.getRoleCode());
    }
}
