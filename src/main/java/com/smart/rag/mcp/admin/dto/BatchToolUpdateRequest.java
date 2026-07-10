package com.smart.rag.mcp.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchToolUpdateRequest(
        @NotEmpty(message = "工具 ID 列表不能为空")
        @Size(max = 1000, message = "单次最多更新 1000 个工具")
        List<@NotNull(message = "工具 ID 不能为空") @Positive(message = "工具 ID 必须为正数") Long> ids
) {}
