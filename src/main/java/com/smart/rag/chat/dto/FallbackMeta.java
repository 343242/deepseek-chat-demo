package com.smart.rag.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 降级元数据 — 当请求由兜底策略降级处理时附带的信息
 * <p>
 * 仅在降级场景出现，非降级场景为 null（序列化时省略）。
 *
 * @param requestedModel 用户原始请求的模型 ID
 * @param fallback       始终为 true，标记这是一次降级响应
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FallbackMeta(
        String requestedModel,
        boolean fallback
) {
}
