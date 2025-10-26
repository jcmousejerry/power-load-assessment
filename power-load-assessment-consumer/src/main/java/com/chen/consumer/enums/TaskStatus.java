package com.chen.consumer.enums;

public enum TaskStatus {
    PENDING(0, "未完成"),
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
}
