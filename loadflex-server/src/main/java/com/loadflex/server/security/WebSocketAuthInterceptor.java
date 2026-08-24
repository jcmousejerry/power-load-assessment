package com.loadflex.server.security;

import io.jsonwebtoken.JwtException;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public WebSocketAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new MessageDeliveryException("WebSocket连接缺少登录凭证");
        }

        try {
            LoginUser loginUser = jwtService.parse(authorization.substring(7));
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    loginUser,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + loginUser.getRoleCode())));
            accessor.setUser(authentication);
        } catch (JwtException exception) {
            throw new MessageDeliveryException("WebSocket登录凭证无效");
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof Authentication)) {
            throw new MessageDeliveryException("WebSocket连接尚未登录");
        }

        Authentication authentication = (Authentication) accessor.getUser();
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        String expectedDestination = "/topic/user/" + loginUser.getUserId();
        if (!expectedDestination.equals(accessor.getDestination())) {
            throw new MessageDeliveryException("不能订阅其他用户的任务通知");
        }
    }
}
