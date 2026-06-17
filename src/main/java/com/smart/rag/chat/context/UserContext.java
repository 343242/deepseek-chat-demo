package com.smart.rag.chat.context;

import java.util.Set;

/**
 * 用户画像 — 当前请求的用户信息
 * <p>
 * 安全边界：permissions 仅供内部策略判断（如 PolicyConstraintResolver），
 * 不注入到 LLM prompt。
 *
 * @param userId      用户 ID
 * @param nickname    用户昵称（用于 prompt 注入）
 * @param roles       角色名称集合（用于 prompt 注入 + 策略判断）
 * @param permissions 权限名称集合（仅内部使用，不注入 LLM）
 */
public record UserContext(
        Long userId,
        String nickname,
        Set<String> roles,
        Set<String> permissions
) {
}
