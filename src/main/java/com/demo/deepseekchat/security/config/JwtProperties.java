package com.demo.deepseekchat.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String secret,
    long accessExpiration,
    long refreshExpiration,
    String issuer,
    String redisPrefix,
    boolean cookieSecure
) {
    /** 开发环境默认 false，生产环境应设为 true */
    public boolean cookieSecure() {
        return cookieSecure;
    }
}
