package com.chen.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.server.entity.UserClusteringResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户聚类结果映射器
 */
@Mapper
public interface UserClusteringResultMapper extends BaseMapper<UserClusteringResult> {
}
