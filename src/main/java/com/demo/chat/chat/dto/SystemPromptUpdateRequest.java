package com.demo.chat.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * System Prompt 更新请求 DTO
 *
 * @param promptText prompt 内容，不能为空
 */
public record SystemPromptUpdateRequest(
    @NotBlank(message = "promptText 不能为空")
    @Size(max = 50000, message = "promptText 过长，最多 50000 字符")
    String promptText
) {}
