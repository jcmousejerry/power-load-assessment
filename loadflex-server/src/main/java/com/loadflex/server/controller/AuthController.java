package com.loadflex.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.AppUser;
import com.loadflex.common.mapper.AppUserMapper;
import com.loadflex.server.api.ApiResponse;
import com.loadflex.server.dto.LoginDTO;
import com.loadflex.server.security.JwtService;
import com.loadflex.server.security.LoginUser;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AppUserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        AppUser user =
                userMapper.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, dto.getUsername()));
        if (user == null
                || !"ACTIVE".equals(user.getStatus())
                || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码不正确");
        }
        LoginUser login = new LoginUser(user.getId(), user.getUsername(), user.getRoleCode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.create(login));
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("displayName", user.getDisplayName());
        data.put("roleCode", user.getRoleCode());
        return ApiResponse.ok("登录成功", data);
    }
}
