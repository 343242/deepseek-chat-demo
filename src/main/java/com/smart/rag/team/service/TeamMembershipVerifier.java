package com.smart.rag.team.service;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.mapper.TeamMapper;
import com.smart.rag.team.mapper.TeamMemberMapper;
import com.smart.rag.team.entity.Team;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 团队成员身份统一校验组件
 * <p>
 * 所有需要团队权限校验的地方统一使用此类，避免重复逻辑。
 * 校验逻辑：
 * <ol>
 *   <li>团队存在且未删除（deleted=0）</li>
 *   <li>成员存在且 status=1</li>
 *   <li>按需校验角色（MEMBER / ADMIN / CREATOR）</li>
 * </ol>
 *
 * @see TeamMemberRole
 */
@Component
public class TeamMembershipVerifier {

    private static final Logger log = LoggerFactory.getLogger(TeamMembershipVerifier.class);

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public TeamMembershipVerifier(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    /**
     * 校验团队成员身份，返回成员记录
     *
     * @param teamId 团队 ID
     * @param userId 用户 ID
     * @return 成员实体
     * @throws BusinessException TEAM_NOT_FOUND / NOT_TEAM_MEMBER
     */
    public TeamMember verifyMember(Long teamId, Long userId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || team.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND);
        }
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null || member.getStatus() != 1) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }
        return member;
    }

    /**
     * 校验管理员或创建者身份
     */
    public TeamMember verifyAdmin(Long teamId, Long userId) {
        TeamMember member = verifyMember(teamId, userId);
        if (member.getRole() != TeamMemberRole.CREATOR
                && member.getRole() != TeamMemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }
        return member;
    }

    /**
     * 校验创建者身份
     */
    public TeamMember verifyCreator(Long teamId, Long userId) {
        TeamMember member = verifyMember(teamId, userId);
        if (member.getRole() != TeamMemberRole.CREATOR) {
            throw new BusinessException(ErrorCode.NOT_TEAM_CREATOR);
        }
        return member;
    }
}
