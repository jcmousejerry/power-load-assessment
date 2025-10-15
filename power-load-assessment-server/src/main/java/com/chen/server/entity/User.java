package com.chen.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户名（全表唯一）
     */
    @TableField("username")
    private String username;

    /**
     * 密码（存储原始密码经过加密后得到的内容）
     */
    @TableField("password")
    private String password;

    /**
     * 头像（头像图片文件存储在minio中的路径）
     */
    @TableField("avatar")
    private String avatar;

    /**
     * 用户类型（0:普通用户, 1:管理员）
     */
    @TableField("user_type")
    private Integer userType;

    /**
     * 用户信息数据创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 用户信息数据更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
