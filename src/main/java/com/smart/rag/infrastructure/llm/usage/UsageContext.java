package com.smart.rag.infrastructure.llm.usage;

import org.jspecify.annotations.Nullable;

/**
 * 一次 LLM 调用的用量归因上下文。
 * <p>
 * 由 {@code ChatModelAssembler} 在装配装饰器时绑定（每请求构造一次装饰器实例，
 * 上下文随构造显式捕获，不依赖 ThreadLocal / Reactor Context）。
 *
 * @param userId         发起调用的用户 ID（显式用户维度，落库 {@code usage_event.user_id}）
 * @param candidateId    候选模型 ID（registry candidate ID）
 * @param scene          调用场景
 * @param conversationId 会话 ID，无会话语境的调用（如意图分类前）可为 {@code null}
 */
public record UsageContext(
    Long userId,
    String candidateId,
    UsageScene scene,
    @Nullable String conversationId
) {
}
