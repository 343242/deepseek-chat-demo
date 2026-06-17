package com.smart.rag.common.team;

/**
 * 团队状态查询服务（只读）。
 * <p>
 * 定义在 common 包，供 rag 等其他模块通过接口依赖注入，
 * 避免直接依赖 team.mapper 造成模块耦合（依赖倒置原则）。
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
