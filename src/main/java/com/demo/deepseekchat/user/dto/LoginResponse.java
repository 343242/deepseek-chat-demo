package com.demo.deepseekchat.user.dto;

import java.util.List;
import java.util.Set;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    long refreshExpiresIn,
    UserInfo user
) {
    public record UserInfo(
        Long id,
        String username,
        String nickname,
        String email,
        String avatar,
        List<String> roles,
        Set<String> permissions
    ) {}
}
