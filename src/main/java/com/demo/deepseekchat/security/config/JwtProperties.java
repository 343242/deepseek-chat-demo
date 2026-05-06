package com.demo.deepseekchat.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String secret,
    long accessExpiration,
    long refreshExpiration,
    String issuer,
    String redisPrefix
) {}
