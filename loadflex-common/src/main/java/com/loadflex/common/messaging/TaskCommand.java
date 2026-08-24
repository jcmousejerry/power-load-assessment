package com.loadflex.common.messaging;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskCommand {
    private String messageId;
    private Long taskId;
    private Long datasetId;
    private String taskType;
    private String objectKey;
    private String mappingJson;
    private String parametersJson;
    private String algorithmVersion;
}
