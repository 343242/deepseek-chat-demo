package com.smart.rag.user.dto;

import java.util.List;

public record LoginResponse(
    UserInfo user
) {
    public record UserInfo(
        Long id,
        String username,
        String nickname,
        String email,
        String avatar,
        List<String> roles
    ) {}
}
