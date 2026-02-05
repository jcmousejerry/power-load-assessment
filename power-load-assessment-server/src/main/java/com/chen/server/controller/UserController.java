package com.chen.server.controller;

import com.chen.server.dto.UserDTO;
import com.chen.server.result.Result;
import com.chen.server.service.AvatarService;
import com.chen.server.service.UserService;
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
        return userService.register(userDTO);
    }

    @PostMapping("/login")
    public Result login(@RequestBody Map<String, String> credentials) {
        return userService.login(credentials);
    }

    @GetMapping("/profile")
    public Result profile() {
        return userService.getProfile();
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
