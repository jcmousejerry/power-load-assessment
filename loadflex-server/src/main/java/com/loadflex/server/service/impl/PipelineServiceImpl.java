package com.loadflex.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.AnalysisPipeline;
import com.loadflex.common.mapper.AnalysisPipelineMapper;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import com.loadflex.server.service.PipelineService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PipelineServiceImpl implements PipelineService {
    private final AnalysisPipelineMapper pipelineMapper;

    public PipelineServiceImpl(AnalysisPipelineMapper pipelineMapper) {
        this.pipelineMapper = pipelineMapper;
    }

    @Override
    public List<AnalysisPipeline> list(Long datasetId) {
        LoginUser loginUser = UserContext.get();
        LambdaQueryWrapper<AnalysisPipeline> query = new LambdaQueryWrapper<AnalysisPipeline>()
                .eq(datasetId != null, AnalysisPipeline::getDatasetId, datasetId)
                .orderByDesc(AnalysisPipeline::getCreatedAt);
        if (!"ADMIN".equals(loginUser.getRoleCode())) {
            query.eq(AnalysisPipeline::getUserId, loginUser.getUserId());
        }
        return pipelineMapper.selectList(query);
    }
}
