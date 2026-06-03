package com.smart.rag.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.team.config.TeamProperties;
import com.smart.rag.team.dto.MemberRoleUpdateRequest;
import com.smart.rag.team.dto.MemberUploadLimitRequest;
import com.smart.rag.team.dto.TeamMemberVO;
import org.springframework.dao.DuplicateKeyException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Map;
import com.smart.rag.team.entity.Team;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.mapper.TeamMapper;
import com.smart.rag.team.mapper.TeamMemberMapper;
import com.smart.rag.team.service.TeamMemberService;
import com.smart.rag.user.entity.SysUser;
import com.smart.rag.user.mapper.SysUserMapper;
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
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }
        if (operator.getRole() != TeamMemberRole.CREATOR && operator.getRole() != TeamMemberRole.ADMIN) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_ADMIN);
        }

        // 校验目标用户存在
        SysUser targetUser = sysUserMapper.selectActiveById(targetUserId)
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.USER_NOT_FOUND));

        return txTemplate.execute(status -> {
            // 校验团队人数上限（事务内防 TOCTOU）
            long memberCount = teamMemberMapper.selectCount(
                    new LambdaQueryWrapper<TeamMember>()
                            .eq(TeamMember::getTeamId, teamId)
                            .eq(TeamMember::getStatus, 1));
            if (memberCount >= teamProperties.getMaxMembersPerTeam()) {
                throw new ClientException(ClientErrorCode.TEAM_MEMBER_LIMIT_EXCEEDED);
            }

            // 校验目标用户加入团队数上限
            long joinedCount = teamMemberMapper.selectCount(
                    new LambdaQueryWrapper<TeamMember>()
                            .eq(TeamMember::getUserId, targetUserId)
                            .eq(TeamMember::getStatus, 1));
            if (joinedCount >= teamProperties.getMaxTeamsPerUser()) {
                throw new ClientException(ClientErrorCode.TEAM_LIMIT_EXCEEDED);
            }

            // 查找是否有历史记录（曾经加入过又退出的）
            TeamMember existing = teamMemberMapper.selectLatestByTeamAndUser(teamId, targetUserId);

            if (existing != null && existing.getStatus() == 1) {
                throw new ClientException(ClientErrorCode.ALREADY_TEAM_MEMBER);
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
                    throw new ClientException(ClientErrorCode.ALREADY_TEAM_MEMBER);
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
            throw new ClientException(ClientErrorCode.CANNOT_REMOVE_SELF);
        }

        // 校验操作者权限
        TeamMember operator = teamMemberMapper.selectByTeamAndUser(teamId, operatorId);
        if (operator == null || (operator.getRole() != TeamMemberRole.CREATOR && operator.getRole() != TeamMemberRole.ADMIN)) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_ADMIN);
        }

        TeamMember target = teamMemberMapper.selectByTeamAndUser(teamId, targetUserId);
        if (target == null) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }

        // 不能移除创建者
        if (target.getRole() == TeamMemberRole.CREATOR) {
            throw new ClientException(ClientErrorCode.CANNOT_REMOVE_CREATOR);
        }

        // 管理员不能移除管理员（只有创建者可以）
        if (operator.getRole() == TeamMemberRole.ADMIN && target.getRole() == TeamMemberRole.ADMIN) {
            throw new ClientException(ClientErrorCode.ADMIN_CANNOT_REMOVE_ADMIN);
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
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }

        // 创建者不能退出（只能解散）
        if (member.getRole() == TeamMemberRole.CREATOR) {
            throw new ClientException(ClientErrorCode.CREATOR_CANNOT_LEAVE);
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
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_CREATOR);
        }

        // 不能改自己的角色
        if (operatorId.equals(targetUserId)) {
            throw new ClientException(ClientErrorCode.CANNOT_CHANGE_OWN_ROLE);
        }

        TeamMember target = teamMemberMapper.selectByTeamAndUser(teamId, targetUserId);
        if (target == null) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }

        // 不能改为 CREATOR（创建者只能通过转让团队变更）
        if (request.targetRole() == TeamMemberRole.CREATOR) {
            throw new ClientException(ClientErrorCode.CANNOT_ASSIGN_CREATOR);
        }

        // 不能修改创建者的角色
        if (target.getRole() == TeamMemberRole.CREATOR) {
            throw new ClientException(ClientErrorCode.CANNOT_CHANGE_CREATOR_ROLE);
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
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_ADMIN);
        }

        TeamMember target = teamMemberMapper.selectByTeamAndUser(teamId, targetUserId);
        if (target == null) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }

        target.setUploadLimitMb(request.uploadLimitMb());
        target.setUpdatedAt(OffsetDateTime.now());
        teamMemberMapper.updateById(target);

        log.info("Member upload limit set: teamId={}, userId={}, limit={}MB, operatorId={}",
                teamId, targetUserId, request.uploadLimitMb(), operatorId);
    }

    @Override
    public PagedResult<TeamMemberVO> listMembers(Long teamId, PageRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 必须是成员才能查看
        TeamMember me = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (me == null) {
            throw new ServiceException(ServiceErrorCode.NOT_TEAM_MEMBER);
        }

        // 分页查询成员
        Page<TeamMember> page = teamMemberMapper.selectPage(req.toPage(),
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getTeamId, teamId)
                        .eq(TeamMember::getStatus, 1)
                        .orderByDesc(TeamMember::getRole));

        if (page.getRecords().isEmpty()) {
            return new PagedResult<>(List.of(), req.page(), req.size(), page.getTotal(), (int) page.getPages());
        }

        // 批量查询用户信息
        List<Long> userIds = page.getRecords().stream().map(TeamMember::getUserId).toList();
        Map<Long, SysUser> userMap = sysUserMapper.selectBatchIds(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(SysUser::getId, u -> u));

        return PagedResult.<TeamMember, TeamMemberVO>of(page, m -> {
            SysUser user = userMap.get(m.getUserId());
            return new TeamMemberVO(
                    m.getUserId(),
                    user != null ? user.getUsername() : "未知",
                    user != null ? user.getNickname() : "未知",
                    m.getRole().name(),
                    m.getUploadLimitMb(),
                    m.getJoinedAt()
            );
        });
    }

    // === 私有方法 ===

    private Team getActiveTeam(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || team.getDeleted() != 0) {
            throw new ServiceException(ServiceErrorCode.TEAM_NOT_FOUND);
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
