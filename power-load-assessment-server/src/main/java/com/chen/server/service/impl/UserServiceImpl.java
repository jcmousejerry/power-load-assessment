package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.UserMapper;
import com.chen.server.result.Result;
import com.chen.server.service.UserService;
import com.chen.server.utils.JwtUtils;
import com.chen.server.vo.UserVO; // 添加VO类导入
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public Result register(User user) {
        // 检查用户名是否已存在
        User existingUser = userMapper.selectOne(new QueryWrapper<User>().eq("username", user.getUsername()));
        if (existingUser != null) {
            return Result.fail("用户名已存在");
        }

        // 加密密码
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // 设置默认用户类型为普通用户
        user.setUserType(UserType.NORMAL.getCode());

        // 保存用户
        userMapper.insert(user);

        return Result.ok("注册成功");
    }

    @Override
    public Result login(String username, String password) {
        // 查找用户
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user == null) {
            return Result.fail("用户名不存在");
        }

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return Result.fail("密码错误");
        }

        // 生成token
        String token = jwtUtils.generateToken(user.getId());

        // 创建UserVO对象并复制需要的属性
        UserVO userVO = new UserVO();
        userVO.setUsername(user.getUsername());
        userVO.setAvatar(user.getAvatar());
        System.out.println(userVO.getAvatar());
        userVO.setUserType(user.getUserType());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", userVO); // 返回UserVO而不是完整的User对象

        return Result.ok(data);
    }
}
