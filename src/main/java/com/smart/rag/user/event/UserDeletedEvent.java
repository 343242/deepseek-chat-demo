package com.smart.rag.user.event;

/**
 * 用户删除事件（design §14.1 R2 — 孤儿资源清理）。
 * <p>
 * 由 {@code SysUserService.deleteUser} 在用户删除后发布（{@link org.springframework.context.ApplicationEventPublisher}）。
 * 监听方（如 BYOK {@code LlmUserLifecycleListener}）据此清理该用户的缓存 / 配置，
 * 避免 llm 模块反向依赖 user 模块（解耦）。
 *
 * @param userId 被删除的用户 ID
 */
public record UserDeletedEvent(Long userId) {
}
