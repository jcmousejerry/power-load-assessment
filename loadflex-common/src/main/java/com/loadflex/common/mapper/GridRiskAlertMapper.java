package com.loadflex.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loadflex.common.entity.GridRiskAlert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface GridRiskAlertMapper extends BaseMapper<GridRiskAlert> {
    @Delete("DELETE FROM grid_risk_alert WHERE id IN ("
            + "SELECT id FROM (SELECT id, ROW_NUMBER() OVER ("
            + "PARTITION BY user_id ORDER BY event_time DESC, id DESC) AS retention_rank "
            + "FROM grid_risk_alert) ranked WHERE retention_rank > #{maxRecords})")
    int deleteOlderThanLatestPerUser(@Param("maxRecords") int maxRecords);

    @Delete("DELETE alert FROM grid_risk_alert alert "
            + "LEFT JOIN grid_user_transformer_monitor monitor "
            + "ON monitor.user_id = alert.user_id AND monitor.transformer_code = alert.transformer_code "
            + "WHERE monitor.id IS NULL")
    int deleteWithoutActiveMonitoring();
}
