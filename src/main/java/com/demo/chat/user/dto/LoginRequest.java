package com.demo.chat.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "用户名不能为空") String username,
    @NotBlank(message = "密码不能为空") String password,
    @NotBlank(message = "验证码ID不能为空") String captchaId,
    @NotBlank(message = "验证码不能为空") String captchaCode
) {}
