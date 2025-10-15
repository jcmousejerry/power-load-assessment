package com.chen.server.vo;

import lombok.Data;

@Data
public class UserVO {
    /**
     * 用户名（全表唯一）
     */
    private String username;

    /**
     * 头像（头像图片文件存储在minio中的路径）
     */
    private String avatar;

    /**
     * 用户类型（0:普通用户, 1:管理员）
     */
    private Integer userType;
}
