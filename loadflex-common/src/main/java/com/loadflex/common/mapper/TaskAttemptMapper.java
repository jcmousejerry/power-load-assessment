package com.loadflex.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loadflex.common.entity.TaskAttempt;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TaskAttemptMapper extends BaseMapper<TaskAttempt> {
    @Select({
        "SELECT attempt.runtime_seconds",
        "FROM task_attempt attempt",
        "INNER JOIN analysis_task task ON task.id = attempt.task_id",
        "WHERE task.task_type = #{taskType}",
        "AND attempt.status = 'SUCCEEDED'",
        "AND attempt.runtime_seconds IS NOT NULL",
        "ORDER BY attempt.finished_at DESC",
        "LIMIT 100"
    })
    List<Integer> selectRecentSuccessfulRuntimes(@Param("taskType") String taskType);
}
