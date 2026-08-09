package com.smart.rag.chat.dto;

/**
 * 取消生成响应 data（design §6.1）。
 *
 * @param cancelled      是否命中活跃流；{@code false} = 流不存在/已结束（幂等）
 * @param conversationId 回显 raw conversationId，与请求体、前端持有值一致
 */
public record CancelStreamResponse(
        boolean cancelled,
        String conversationId
) {}
