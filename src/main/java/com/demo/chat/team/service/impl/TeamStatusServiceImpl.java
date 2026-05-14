package com.demo.chat.team.service.impl;

import com.demo.chat.common.team.TeamStatusService;
import com.demo.chat.team.entity.Team;
import com.demo.chat.team.entity.TeamMember;
import com.demo.chat.team.mapper.TeamMapper;
import com.demo.chat.team.mapper.TeamMemberMapper;
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
        return team != null && team.getDeleted() == 0;
    }

    @Override
    public boolean isTeamMember(Long teamId, Long userId) {
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        return member != null && member.getStatus() == 1;
    }
}
