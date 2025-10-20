package com.chen.server.controller;

import com.chen.server.dto.UserDTO;
import com.chen.server.entity.User;
import com.chen.server.result.Result;
import com.chen.server.service.AvatarService;
import com.chen.server.service.UserService;
import com.chen.server.utils.LoginUserHolder;
import com.chen.server.vo.UserVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AvatarService avatarService;

    @PostMapping("/register")
    public Result register(@RequestBody UserDTO userDTO) {
        // 将DTO转换为实体对象
        User user = new User();
        user.setUsername(userDTO.getUsername());
        user.setPassword(userDTO.getPassword());
        return userService.register(user);
    }

    @PostMapping("/login")
    public Result login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");
        return userService.login(username, password);
    }

    @GetMapping("/profile")
    public Result profile() {
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

    /**
     * 上传用户头像
     */
    @PostMapping("/avatar")
    public Result uploadAvatar(@RequestParam("avatar") MultipartFile file) {
        return avatarService.uploadAvatar(file);
    }

    /**
     * 获取当前用户头像URL
     */
    @GetMapping("/avatar-url")
    public Result getAvatarUrl() {
        return avatarService.getCurrentUserAvatarUrl();
    }
}
