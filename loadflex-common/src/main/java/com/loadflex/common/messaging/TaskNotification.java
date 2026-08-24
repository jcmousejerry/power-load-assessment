package com.loadflex.common.messaging;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TaskNotification {
    private Long taskId;
    private Long userId;
    private String status;
    private String stage;
    private double progress;
    private String message;
}
