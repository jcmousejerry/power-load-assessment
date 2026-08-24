package com.loadflex.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final Key key;
    private final long expirationMillis;

    public JwtService(
            @Value("${loadflex.jwt.secret}") String secret,
            @Value("${loadflex.jwt.expiration-seconds}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationSeconds * 1000;
    }

    public String create(LoginUser user) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(user.getUsername())
                .claim("uid", user.getUserId())
                .claim("role", user.getRoleCode())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expirationMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public LoginUser parse(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return new LoginUser(
                ((Number) claims.get("uid")).longValue(), claims.getSubject(), String.valueOf(claims.get("role")));
    }
}
