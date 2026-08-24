package com.loadflex.server.security;

public final class UserContext {
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalArgumentException("用户未登录或登录已失效");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
