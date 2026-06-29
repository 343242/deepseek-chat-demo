package com.smart.rag.mcp.core;

/**
 * 调用方主体（领域值对象）。
 * <p>
 * Phase 1 仅 {@code (userId, teamId)}，<b>无 roles 字段</b>——项目无 role provider，
 * 故 yaml {@code roles}/risk/quota 本期无判定对象（design R-7）；Phase 1 authz 收窄为
 * allowlist + intent 路由 + subject 存在性，roles/risk/quota 强制接 Agent 链前补 role source。
 * <p>
 * 由消费侧从 {@code ToolWorkspace.getUserId()/getTeamId()} 构造
 * （{@code new Subject(ws.getUserId(), ws.getTeamId())}，<b>非</b> {@code workspace.subject()}——该方法不存在）。
 *
 * @param userId 调用方用户 id；{@code <= 0} 视为未认证（anonymous），authz 拒绝
 * @param teamId 调用方团队 id；可空（无团队上下文）
 */
public record Subject(long userId, Long teamId) {

    /** 是否已认证（userId 有效）——内核 authz 的 subject 存在性判定用（§8）。 */
    public boolean isAuthenticated() {
        return userId > 0;
    }
}
