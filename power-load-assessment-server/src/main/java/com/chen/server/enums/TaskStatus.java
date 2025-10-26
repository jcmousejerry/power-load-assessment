package com.chen.server.enums;

/**
 * 数据分析任务状态枚举
 */
public enum TaskStatus {
    /**
     * 未完成
     */
    PENDING(0, "未完成"),

    /**
     * 已完成
     */
    COMPLETED(1, "已完成");

    private final Integer code;
    private final String description;

    TaskStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据code获取TaskStatus枚举
     *
     * @param code 任务状态code
     * @return TaskStatus枚举
     */
    public static TaskStatus fromCode(Integer code) {
        for (TaskStatus taskStatus : TaskStatus.values()) {
            if (taskStatus.getCode().equals(code)) {
                return taskStatus;
            }
        }
        throw new IllegalArgumentException("未知的任务状态code: " + code);
    }
}
