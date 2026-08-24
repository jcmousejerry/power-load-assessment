package com.loadflex.server.security;

import io.jsonwebtoken.JwtException;
import java.util.Collections;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    public JwtFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws java.io.IOException, javax.servlet.ServletException {
        try {
            String value = req.getHeader(HttpHeaders.AUTHORIZATION);
            if (value != null && value.startsWith("Bearer ")) {
                LoginUser user = jwtService.parse(value.substring(7));
                UserContext.set(user);
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRoleCode()))));
            }
            chain.doFilter(req, res);
        } catch (JwtException e) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"success\":false,\"message\":\"登录已失效，请重新登录\",\"data\":null}");
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
