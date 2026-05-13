package com.demo.chat.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import com.demo.chat.team.config.TeamProperties;
import com.demo.chat.team.dto.MemberRoleUpdateRequest;
import com.demo.chat.team.dto.MemberUploadLimitRequest;
import com.demo.chat.team.dto.TeamMemberVO;
import org.springframework.dao.DuplicateKeyException;
import com.demo.chat.team.entity.Team;
import com.demo.chat.team.entity.TeamMember;
import com.demo.chat.team.enums.TeamMemberRole;
import com.demo.chat.team.mapper.TeamMapper;
import com.demo.chat.team.mapper.TeamMemberMapper;
import com.demo.chat.team.service.TeamMemberService;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 团队成员服务实现
 */
@Service
public class TeamMemberServiceImpl implements TeamMemberService {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberServiceImpl.class);

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final SysUserMapper sysUserMapper;
    private final TeamProperties teamProperties;
    private final TransactionTemplate txTemplate;

    public TeamMemberServiceImpl(TeamMapper teamMapper,
                                 TeamMemberMapper teamMemberMapper,
                                 SysUserMapper sysUserMapper,
                                 TeamProperties teamProperties,
                                 TransactionTemplate txTemplate) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.teamProperties = teamProperties;
        this.txTemplate = txTemplate;
    }

    @Override
    public TeamMemberVO addMember(Long teamId, Long targetUserId) {
        Long operatorId = SecurityUtils.getCurrentUserId();

        // 校验团队存在
        Team team = getActiveTeam(teamId);

        // 校验操作者权限（创建者或管理员）
        TeamMember operator = teamMemberMapper.selectByTeamAndUser(teamId, operatorId);
        if (operator == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }
        if (operator.getRole() != TeamMemberRole.CREATOR && operator.getRole() != TeamMemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }

        // 校验目标用户存在
        SysUser targetUser = sysUserMapper.selectActiveById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return txTemplate.execute(status -> {
            // 校验团队人数上限（事务内防 TOCTOU）
            long memberCount = teamMemberMapper.selectCount(
                    new LambdaQueryWrapper<TeamMember>()
                            .eq(TeamMember::getTeamId, teamId)
                            .eq(TeamMember::getStatus, 1));
            if (memberCount >= teamProperties.getMaxMembersPerTeam()) {
                throw new BusinessException(ErrorCode.TEAM_MEMBER_LIMIT_EXCEEDED);
            }

            // 校验目标用户加入团队数上限
            long joinedCount = teamMemberMapper.selectCount(
                    new LambdaQueryWrapper<TeamMember>()
                            .eq(TeamMember::getUserId, targetUserId)
                            .eq(TeamMember::getStatus, 1));
            if (joinedCount >= teamProperties.getMaxTeamsPerUser()) {
                throw new BusinessException(ErrorCode.TEAM_LIMIT_EXCEEDED);
            }

            // 查找是否有历史记录（曾经加入过又退出的）
            TeamMember existing = teamMemberMapper.selectOne(
                    new LambdaQueryWrapper<TeamMember>()
                            .eq(TeamMember::getTeamId, teamId)
                            .eq(TeamMember::getUserId, targetUserId)
                            .last("LIMIT 1"));

            if (existing != null && existing.getStatus() == 1) {
                throw new BusinessException(ErrorCode.ALREADY_TEAM_MEMBER);
            }

            TeamMember member;
            if (existing != null) {
                existing.setStatus(1);
                existing.setRole(TeamMemberRole.MEMBER);
                existing.setUploadLimitMb(team.getDefaultUploadLimitMb());
                existing.setJoinedAt(OffsetDateTime.now());
                existing.setUpdatedAt(OffsetDateTime.now());
                teamMemberMapper.updateById(existing);
                member = existing;
            } else {
                member = new TeamMember();
                member.setTeamId(teamId);
                member.setUserId(targetUserId);
                member.setRole(TeamMemberRole.MEMBER);
                member.setUploadLimitMb(team.getDefaultUploadLimitMb());
                member.setStatus(1);
                member.setJoinedAt(OffsetDateTime.now());
                member.setUpdatedAt(OffsetDateTime.now());
                try {
                    teamMemberMapper.insert(member);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException(ErrorCode.ALREADY_TEAM_MEMBER);
                }
            }

            log.info("Member added: teamId={}, userId={}, operatorId={}", teamId, targetUserId, operatorId);
            return toMemberVO(member, targetUser);
        });
    }

    @Override
    public void removeMember(Long teamId, Long targetUserId) {
        Long operatorId = SecurityUtils.getCurrentUserId();

        // 校验团队存在
        getActiveTeam(teamId);

        // 不能移除自己（用 leave）
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.CANNOT_REMOVE_SELF);
        }

        // 校验操作者权限
        TeamMember operator = teamMemberMapper.selectByTeamAndUser(teamId, operatorId);
        if (operator == null || (operator.getRole() != TeamMemberRole.CREATOR && operator.getRole() != TeamMemberRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }

        TeamMember target = teamMemberMapper.selectByTeamAndUser(teamId, targetUserId);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        // 不能移除创建者
        if (target.getRole() == TeamMemberRole.CREATOR) {
            throw new BusinessException(ErrorCode.CANNOT_REMOVE_CREATOR);
        }

        // 管理员不能移除管理员（只有创建者可以）
        if (operator.getRole() == TeamMemberRole.ADMIN && target.getRole() == TeamMemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_TEAM_CREATOR);
        }

        target.setStatus(0);
        target.setUpdatedAt(OffsetDateTime.now());
        teamMemberMapper.updateById(target);

        log.info("Member removed: teamId={}, userId={}, operatorId={}", teamId, targetUserId, operatorId);
    }

    @Override
    public void leaveTeam(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        // 创建者不能退出（只能解散）
        if (member.getRole() == TeamMemberRole.CREATOR) {
            throw new BusinessException(ErrorCode.CREATOR_CANNOT_LEAVE);
        }

        member.setStatus(0);
        member.setUpdatedAt(OffsetDateTime.now());
        teamMemberMapper.updateById(member);

        log.info("Member left: teamId={}, userId={}", teamId, userId);
    }

    @Override
    public void updateMemberRole(Long teamId, Long targetUserId, MemberRoleUpdateRequest request) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 仅创建者可改角色
        TeamMember operator = teamMemberMapper.selectByTeamAndUser(teamId, operatorId);
        if (operator == null || operator.getRole() != TeamMemberRole.CREATOR) {
            throw new BusinessException(ErrorCode.NOT_TEAM_CREATOR);
        }

        // 不能改自己的角色
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.CANNOT_CHANGE_OWN_ROLE);
        }

        TeamMember target = teamMemberMapper.selectByTeamAndUser(teamId, targetUserId);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        // 不能改为 CREATOR（创建者只能通过转让团队变更）
        if (request.targetRole() == TeamMemberRole.CREATOR) {
            throw new BusinessException(ErrorCode.CANNOT_ASSIGN_CREATOR);
        }

        target.setRole(request.targetRole());
        target.setUpdatedAt(OffsetDateTime.now());
        teamMemberMapper.updateById(target);

        log.info("Member role updated: teamId={}, userId={}, newRole={}, operatorId={}",
                teamId, targetUserId, request.targetRole(), operatorId);
    }

    @Override
    public void setMemberUploadLimit(Long teamId, Long targetUserId, MemberUploadLimitRequest request) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        TeamMember operator = teamMemberMapper.selectByTeamAndUser(teamId, operatorId);
        if (operator == null || (operator.getRole() != TeamMemberRole.CREATOR && operator.getRole() != TeamMemberRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }

        TeamMember target = teamMemberMapper.selectByTeamAndUser(teamId, targetUserId);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        target.setUploadLimitMb(request.uploadLimitMb());
        target.setUpdatedAt(OffsetDateTime.now());
        teamMemberMapper.updateById(target);

        log.info("Member upload limit set: teamId={}, userId={}, limit={}MB, operatorId={}",
                teamId, targetUserId, request.uploadLimitMb(), operatorId);
    }

    @Override
    public List<TeamMemberVO> listMembers(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 必须是成员才能查看
        TeamMember me = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (me == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        List<TeamMember> members = teamMemberMapper.selectList(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getTeamId, teamId)
                        .eq(TeamMember::getStatus, 1)
                        .orderByDesc(TeamMember::getRole));

        return members.stream().map(m -> {
            SysUser user = sysUserMapper.selectActiveById(m.getUserId()).orElse(null);
            return new TeamMemberVO(
                    m.getUserId(),
                    user != null ? user.getUsername() : "未知",
                    user != null ? user.getNickname() : "未知",
                    m.getRole().name(),
                    m.getUploadLimitMb(),
                    m.getJoinedAt()
            );
        }).toList();
    }

    // === 私有方法 ===

    private Team getActiveTeam(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || team.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND);
        }
        return team;
    }

    private TeamMemberVO toMemberVO(TeamMember member, SysUser user) {
        return new TeamMemberVO(
                member.getUserId(),
                user.getUsername(),
                user.getNickname(),
                member.getRole().name(),
                member.getUploadLimitMb(),
                member.getJoinedAt()
        );
    }
}
