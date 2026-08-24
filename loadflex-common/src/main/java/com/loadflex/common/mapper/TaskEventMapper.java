package com.loadflex.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loadflex.common.entity.TaskEvent;
import org.apache.ibatis.annotations.Select;

public interface TaskEventMapper extends BaseMapper<TaskEvent> {
    @Select("SELECT COALESCE(MAX(event_seq),0) FROM task_event WHERE task_id=#{taskId}")
    Long maxSeq(Long taskId);
}
