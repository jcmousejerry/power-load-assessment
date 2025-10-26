package com.chen.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.server.dto.UserDTO;
import com.chen.server.entity.User;
import com.chen.server.enums.UserType;
import com.chen.server.mapper.UserMapper;
import com.chen.server.result.Result;
import com.chen.server.service.AvatarService;
import com.chen.server.service.UserService;
import com.chen.server.utils.JwtUtils;
import com.chen.server.utils.LoginUserHolder;
import com.chen.server.vo.UserVO;
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

    @Autowired
    private AvatarService avatarService;

    @Override
    public Result register(UserDTO userDTO) {
        // 检查用户名是否已存在
        User existingUser = userMapper.selectOne(new QueryWrapper<User>().eq("username", userDTO.getUsername()));
        if (existingUser != null) {
            return Result.fail("用户名已存在");
        }

        // 创建用户实体对象
        User user = new User();
        user.setUsername(userDTO.getUsername());
        user.setPassword(passwordEncoder.encode(userDTO.getPassword()));

        // 设置默认用户类型为普通用户
        user.setUserType(UserType.NORMAL.getCode());

        // 保存用户
        userMapper.insert(user);

        return Result.ok("注册成功");
    }

    @Override
    public Result login(Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

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
        userVO.setUserType(user.getUserType());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", userVO);

        // 如果用户有头像，生成头像URL
        if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            Result avatarResult = avatarService.getAvatarUrl(user.getAvatar());
            if (avatarResult.getSuccess()) {
                data.put("avatarInfo", avatarResult.getData());
            }
        }

        return Result.ok(data);
    }

    @Override
    public Result getProfile() {
        // 获取当前登录用户信息
        User currentUser = LoginUserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("未获取到用户信息");
        }

        // 创建返回的用户信息VO
        UserVO userVO = new UserVO();
        userVO.setUsername(currentUser.getUsername());
        userVO.setAvatar(currentUser.getAvatar());
        userVO.setUserType(currentUser.getUserType());

        return Result.ok(userVO);
    }
}
