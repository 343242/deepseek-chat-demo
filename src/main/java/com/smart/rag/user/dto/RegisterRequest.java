package com.smart.rag.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "用户名不能为空") @Size(min = 2, max = 50, message = "用户名长度必须为2到50个字符") String username,
    @NotBlank(message = "密码不能为空") @Size(min = 8, max = 72, message = "密码长度必须为8到72个字符") String password,
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") @Size(max = 100, message = "邮箱最多100个字符") String email,
    @Size(max = 50, message = "昵称最多50个字符") String nickname,
    @NotBlank(message = "验证码ID不能为空") String captchaId,
    @NotBlank(message = "验证码不能为空") String captchaCode
) {}
