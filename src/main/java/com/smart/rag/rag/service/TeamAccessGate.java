package com.smart.rag.rag.service;

/**
 * 团队访问端口（rag 侧定义，team 模块提供适配器实现）。
 * <p>
 * 依赖倒置：rag 文档域需要「团队成员校验 / 团队活跃状态」能力，
 * 但不应反向依赖 team 模块。本接口由 team 侧 {@code TeamAccessGateAdapter}
 * 适配实现，rag 仅依赖此端口，从而切断 rag → team 的反向依赖。
 * <p>
 * 方法语义与原 team 侧 {@code TeamMembershipVerifier} / {@code TeamStatusService}
 * 一一对应，仅收敛为 rag 所需的最窄能力（是否管理者、团队是否活跃），
 * 不让 team 的实体/枚举穿透进 rag。
 */
public interface TeamAccessGate {

    /**
     * 校验团队成员身份，返回访问上下文。
     *
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @return 访问上下文（含是否为管理者）
     * @throws com.smart.rag.infrastructure.exception.ServiceException 团队不存在 / 非成员
     */
    TeamAccess verifyAccess(Long teamId, Long userId);

    /**
     * 判断团队是否处于活跃状态（存在且未软删）。
     *
     * @param teamId 团队 ID
     * @return true 表示团队活跃
     */
    boolean isTeamActive(Long teamId);

    /**
     * 团队访问上下文。
     *
     * @param manager 是否为管理者（团队 ADMIN / CREATOR）
     */
    record TeamAccess(boolean manager) {
    }
}
