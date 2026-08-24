package com.loadflex.server.dto;

import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateTaskDTO {
    @NotBlank(message = "请选择任务类型")
    private String taskType;

    private String taskName;

    @NotNull(message = "请选择数据集")
    private Long datasetId;

    private Map<String, Object> parameters;
}
