package com.demo.deepseekchat.security.util;

import com.demo.deepseekchat.security.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final Set<String> KNOWN_DEFAULTS = Set.of(
            "myDefaultSecretKeyForDevOnlyMustBe32CharsLong!!"
    );

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = Keys.hmacShaKeyFor(
            jwtProperties.secret().getBytes(StandardCharsets.UTF_8)
        );
    }

    @PostConstruct
    void validateSecret() {
        String secret = jwtProperties.secret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                "JWT secret 必须至少 32 个字符，当前长度: "
                + (secret == null ? 0 : secret.length()));
        }
        if (KNOWN_DEFAULTS.contains(secret)) {
            throw new IllegalStateException(
                "JWT secret 不能使用已知默认值，请通过环境变量 JWT_SECRET 设置一个安全的密钥");
        }
    }

    public String generateAccessToken(Long userId, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.accessExpiration() * 1000);
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
            .subject(String.valueOf(userId))
            .id(jti)
            .claim("roles", roles)
            .claim("type", "access")
            .issuer(jwtProperties.issuer())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact();
    }

    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.refreshExpiration() * 1000);
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
            .subject(String.valueOf(userId))
            .id(jti)
            .claim("type", "refresh")
            .issuer(jwtProperties.issuer())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseVerifiedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseVerifiedClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    public String getTokenType(String token) {
        Claims claims = parseVerifiedClaims(token);
        return claims.get("type", String.class);
    }

    public String getJtiFromToken(String token) {
        Claims claims = parseVerifiedClaims(token);
        return claims.getId();
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseVerifiedClaims(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = parseVerifiedClaims(token);
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    /**
     * 解析并验证 JWT：签名 + issuer
     */
    private Claims parseVerifiedClaims(String token) {
        return Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(jwtProperties.issuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
