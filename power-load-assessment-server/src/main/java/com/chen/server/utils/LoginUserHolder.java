package com.chen.server.utils;

import com.chen.server.entity.User;
import com.chen.server.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LoginUserHolder {

    private static final ThreadLocal<User> USER_HOLDER = new ThreadLocal<>();

    public static void setUser(User user) {
        USER_HOLDER.set(user);
    }

    public static User getUser() {
        return USER_HOLDER.get();
    }

    public static Long getUserId() {
        User user = USER_HOLDER.get();
        return user != null ? user.getId() : null;
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}
