package com.loadflex.server.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginUser {
    private final Long userId;
    private final String username;
    private final String roleCode;
}
