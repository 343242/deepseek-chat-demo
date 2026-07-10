package com.smart.rag.mcp.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBearerTokenRequest(
        @NotBlank(message = "Bearer Token 不能为空")
        @Size(max = 8192, message = "Bearer Token 不能超过 8192 个字符") String bearerToken
) {}
