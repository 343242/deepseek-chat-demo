package com.smart.rag.user.dto;

import java.util.List;

public record LoginResponse(
    UserInfo user
) {
    /**
     * 已认证用户信息。
     *
     * @param id          用户 ID
     * @param username    登录名
     * @param nickname    昵称
     * @param email       邮箱
     * @param avatar      头像 URL
     * @param roles       角色名列表（如 ADMIN / USER）
     * @param permissions 权限码列表（如 chat:send / conversation:manage）。
     *                    前端权限守卫据此渲染菜单与路由，避免硬编码"角色→权限"映射表。
     *                    <p>登录/注册/刷新响应中可能为空（权限预热是异步 best-effort），
     *                    前端应在拿到响应后立即调 {@code GET /api/auth/me} 兜底拉取；
     *                    {@code /api/auth/me} 保证返回非空的当前权限快照。
     */
    public record UserInfo(
        Long id,
        String username,
        String nickname,
        String email,
        String avatar,
        List<String> roles,
        List<String> permissions
    ) {}
}
