package com.smart.rag.team.service.impl;

import com.smart.rag.common.team.TeamStatusService;
import com.smart.rag.team.entity.Team;
import com.smart.rag.team.enums.TeamStatus;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.mapper.TeamMapper;
import com.smart.rag.team.mapper.TeamMemberMapper;
import org.springframework.stereotype.Service;

/**
 * 团队状态查询服务实现。
 * <p>
 * 供其他模块（如 rag.upload）通过 {@link TeamStatusService} 接口注入，
 * 避免跨模块直接依赖 Mapper。
 */
@Service
public class TeamStatusServiceImpl implements TeamStatusService {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public TeamStatusServiceImpl(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    @Override
    public boolean isTeamActive(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        // deleted 负责软删除/解散，status 负责启用/禁用：二者都满足才算活跃
        return team != null && team.getDeleted() == 0 && team.getStatus() == TeamStatus.ACTIVE;
    }

    @Override
    public boolean isTeamMember(Long teamId, Long userId) {
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        return member != null && member.getStatus() == 1;
    }
}
