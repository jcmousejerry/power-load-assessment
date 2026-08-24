package com.loadflex.server.dto;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginDTO {
    @NotBlank(message = "请输入用户名")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;
}
