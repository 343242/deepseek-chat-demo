package com.smart.rag.infrastructure.agent.workspace;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * ToolWorkspace 工厂 — 按请求创建 Workspace 实例
 * <p>
 * 每次请求创建独立的 ToolWorkspace，通过闭包传递给 Tool 和 Advisor，
 * 请求结束后由 GC 回收。
 */
@Component
public class ToolWorkspaceFactory {

    /**
     * 创建请求级 Workspace
     *
     * @param userId 用户 ID
     * @param teamId 团队 ID（可空）
     * @return 新的 ToolWorkspace 实例
     */
    public ToolWorkspace create(long userId, @Nullable Long teamId) {
        return new ToolWorkspace(userId, teamId);
    }
}
