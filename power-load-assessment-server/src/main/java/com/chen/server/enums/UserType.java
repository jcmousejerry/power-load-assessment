package com.chen.server.enums;

public enum UserType {
    /**
     * 普通用户
     */
    NORMAL(0, "普通用户"),

    /**
     * 管理员用户
     */
    ADMIN(1, "管理员");

    private final Integer code;
    private final String description;

    UserType(Integer code, String description) {
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
     * 根据code获取UserType枚举
     *
     * @param code 用户类型code
     * @return UserType枚举
     */
    public static UserType fromCode(Integer code) {
        for (UserType userType : UserType.values()) {
            if (userType.getCode().equals(code)) {
                return userType;
            }
        }
        throw new IllegalArgumentException("未知的用户类型code: " + code);
    }
}
