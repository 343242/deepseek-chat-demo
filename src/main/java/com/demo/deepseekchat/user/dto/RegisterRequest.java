package com.demo.deepseekchat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "用户名不能为空") String username,
    @NotBlank(message = "密码不能为空") @Size(min = 8, message = "密码至少8位") String password,
    @Size(max = 50, message = "昵称最多50个字符") String nickname
) {}
