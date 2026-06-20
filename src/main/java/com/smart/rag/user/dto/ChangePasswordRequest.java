package com.smart.rag.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "旧密码不能为空") String oldPassword,
    @NotBlank(message = "新密码不能为空") @Size(min = 8, max = 72, message = "新密码需8-72位") String newPassword
) {}
