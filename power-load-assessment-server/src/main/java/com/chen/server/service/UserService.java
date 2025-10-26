package com.chen.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chen.server.dto.UserDTO;
import com.chen.server.entity.User;
import com.chen.server.result.Result;

import java.util.Map;

public interface UserService extends IService<User> {

    Result register(UserDTO userDTO);

    Result login(Map<String, String> credentials);

    Result getProfile();
}
