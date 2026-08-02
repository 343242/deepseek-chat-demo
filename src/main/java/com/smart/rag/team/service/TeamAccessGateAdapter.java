package com.smart.rag.team.service;

import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.enums.TeamMemberRole;
import org.springframework.stereotype.Component;

/**
 * {@link TeamAccessGate} 的 team 侧适配实现。
 * <p>
 * 桥接 rag 文档域端口与 team 模块内部能力（{@link TeamMembershipVerifier} /
 * {@link TeamStatusService}），将 team 的角色枚举收敛为 rag 所需的「是否管理者」布尔，
 * 使 rag 无需反向依赖 team。
 */
@Component
public class TeamAccessGateAdapter implements TeamAccessGate {

    private final TeamMembershipVerifier teamMembershipVerifier;
    private final TeamStatusService teamStatusService;

    public TeamAccessGateAdapter(TeamMembershipVerifier teamMembershipVerifier,
                                 TeamStatusService teamStatusService) {
        this.teamMembershipVerifier = teamMembershipVerifier;
        this.teamStatusService = teamStatusService;
    }

    @Override
    public TeamAccess verifyAccess(Long teamId, Long userId) {
        TeamMember member = teamMembershipVerifier.verifyMember(teamId, userId);
        TeamMemberRole role = member.getRole();
        return new TeamAccess(role == TeamMemberRole.ADMIN || role == TeamMemberRole.CREATOR);
    }

    @Override
    public boolean isTeamActive(Long teamId) {
        return teamStatusService.isTeamActive(teamId);
    }
}
