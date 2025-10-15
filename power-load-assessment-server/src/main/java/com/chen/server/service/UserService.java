package com.chen.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chen.server.entity.User;
import com.chen.server.result.Result;

public interface UserService extends IService<User> {

    Result register(User user);

    Result login(String username, String password);
}
