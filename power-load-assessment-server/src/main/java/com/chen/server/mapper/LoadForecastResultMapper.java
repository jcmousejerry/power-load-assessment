package com.chen.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.server.entity.LoadForecastResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 负荷预测结果映射器
 */
@Mapper
public interface LoadForecastResultMapper extends BaseMapper<LoadForecastResult> {
}
