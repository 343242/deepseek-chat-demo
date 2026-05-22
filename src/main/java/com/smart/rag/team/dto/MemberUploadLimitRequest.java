package com.smart.rag.team.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MemberUploadLimitRequest(
    @NotNull(message = "上传额度不能为空")
    @Min(value = 1, message = "上传额度最小1MB")
    @Max(value = 10240, message = "上传额度最大10240MB")
    Long uploadLimitMb
) {}
