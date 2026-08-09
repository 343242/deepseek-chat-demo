package com.smart.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 取消生成请求 DTO（design §6.1）。
 *
 * @param conversationId 对话 ID（raw 形式，前端已持有；服务端 buildIsolatedId 后查 registry）
 * @param reason         取消原因（枚举，Jackson 反序列化自带校验；可选，默认 USER_ABORT）
 */
public record CancelStreamRequest(
        @NotBlank(message = "对话 ID 不能为空")
        @Size(max = 100, message = "对话 ID 过长")
        @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "对话 ID 仅允许字母、数字、下划线和连字符")
        String conversationId,

        CancelReason reason
) {
    /** 取消原因，默认 USER_ABORT */
    public CancelReason reason() {
        return reason != null ? reason : CancelReason.USER_ABORT;
    }
}
