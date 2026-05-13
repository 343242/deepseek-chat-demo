package com.demo.chat.team.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MemberUploadLimitRequest(
    @NotNull(message = "上传额度不能为空")
    @Min(value = 1, message = "上传额度最小1MB")
    Long uploadLimitMb
) {}
