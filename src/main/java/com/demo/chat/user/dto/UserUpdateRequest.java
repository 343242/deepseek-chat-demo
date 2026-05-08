package com.demo.chat.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
    @Size(max = 50, message = "昵称最多50个字符") String nickname,
    @Email(message = "邮箱格式不正确") @Size(max = 100, message = "邮箱过长") String email,
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
    @Size(max = 255, message = "头像URL过长") String avatar
) {}
