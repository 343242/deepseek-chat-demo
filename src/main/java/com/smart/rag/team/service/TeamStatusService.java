package com.smart.rag.team.service;

/**
 * 团队状态查询服务（只读）。
 * <p>
 * 归属于 team 模块（team 业务契约应由 team 模块自有，不应下沉到 common
 * 污染基础设施层的中立性）。跨模块调用方按需注入本接口。
 */
public interface TeamStatusService {

    /**
     * 判断团队是否处于活跃状态（存在且未软删）。
     *
     * @param teamId 团队 ID
     * @return true = 团队活跃
     */
    boolean isTeamActive(Long teamId);

    /**
     * 判断用户是否是指定团队的活跃成员。
     *
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @return true = 是活跃成员
     */
    boolean isTeamMember(Long teamId, Long userId);
}
