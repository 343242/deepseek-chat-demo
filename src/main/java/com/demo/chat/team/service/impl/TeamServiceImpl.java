package com.demo.chat.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import com.demo.chat.team.config.TeamProperties;
import com.demo.chat.team.dto.*;
import com.demo.chat.team.entity.Team;
import com.demo.chat.team.entity.TeamMember;
import com.demo.chat.team.enums.TeamMemberRole;
import com.demo.chat.team.enums.TeamStatus;
import com.demo.chat.team.mapper.TeamMapper;
import com.demo.chat.team.mapper.TeamMemberMapper;
import com.demo.chat.team.service.TeamService;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.rag.upload.BucketResolver;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 团队服务实现
 * <p>
 * 编程式事务（{@link TransactionTemplate}）用于多表写入。
 */
@Service
public class TeamServiceImpl implements TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamServiceImpl.class);

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final SysUserMapper sysUserMapper;
    private final TeamProperties teamProperties;
    private final TransactionTemplate txTemplate;
    private final FileStorageService fileStorageService;
    private final BucketResolver bucketResolver;

    public TeamServiceImpl(TeamMapper teamMapper,
                           TeamMemberMapper teamMemberMapper,
                           SysUserMapper sysUserMapper,
                           TeamProperties teamProperties,
                           TransactionTemplate txTemplate,
                           FileStorageService fileStorageService,
                           BucketResolver bucketResolver) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.teamProperties = teamProperties;
        this.txTemplate = txTemplate;
        this.fileStorageService = fileStorageService;
        this.bucketResolver = bucketResolver;
    }

    @Override
    public TeamVO createTeam(TeamCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 校验用户加入团队数上限
        long joinedCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getUserId, userId)
                        .eq(TeamMember::getStatus, 1));
        if (joinedCount >= teamProperties.getMaxTeamsPerUser()) {
            throw new BusinessException(ErrorCode.TEAM_LIMIT_EXCEEDED);
        }

        return txTemplate.execute(status -> {
            // 1. 创建团队
            Team team = new Team();
            team.setTeamName(request.teamName());
            team.setTeamDesc(request.teamDesc());
            team.setCreatorId(userId);
            team.setDefaultUploadLimitMb(teamProperties.getDefaultMemberUploadLimitMb());
            team.setCreatorUploadLimitMb(teamProperties.getDefaultCreatorUploadLimitMb());
            team.setStatus(TeamStatus.ACTIVE);
            team.setDeleted(0);
            team.setCreatedAt(OffsetDateTime.now());
            team.setUpdatedAt(OffsetDateTime.now());
            try {
                teamMapper.insert(team);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.TEAM_NAME_DUPLICATE);
            }

            // 1.5 创建团队专属 MinIO bucket
            String teamBucket = bucketResolver.resolve(team.getId());
            fileStorageService.ensureBucketExists(teamBucket);

            // 2. 创建者自动成为 CREATOR 成员
            TeamMember creatorMember = new TeamMember();
            creatorMember.setTeamId(team.getId());
            creatorMember.setUserId(userId);
            creatorMember.setRole(TeamMemberRole.CREATOR);
            creatorMember.setUploadLimitMb(teamProperties.getDefaultCreatorUploadLimitMb());
            creatorMember.setStatus(1);
            creatorMember.setJoinedAt(OffsetDateTime.now());
            creatorMember.setUpdatedAt(OffsetDateTime.now());
            teamMemberMapper.insert(creatorMember);

            log.info("Team created: id={}, name={}, creatorId={}", team.getId(), team.getTeamName(), userId);
            return toTeamVO(team, 1, TeamMemberRole.CREATOR.name());
        });
    }

    @Override
    public TeamDetailVO getTeamDetail(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Team team = getActiveTeam(teamId);

        // 必须是成员才能查看详情
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        // 成员数
        long memberCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getTeamId, teamId)
                        .eq(TeamMember::getStatus, 1));

        // 创建者名称
        String creatorName = sysUserMapper.selectActiveById(team.getCreatorId())
                .map(SysUser::getNickname)
                .orElse("未知");

        return new TeamDetailVO(
                team.getId(),
                team.getTeamName(),
                team.getTeamDesc(),
                team.getCreatorId(),
                creatorName,
                (int) memberCount,
                0, // documentCount 后续 Phase 4 补充
                team.getDefaultUploadLimitMb(),
                team.getCreatorUploadLimitMb(),
                member.getRole().name(),
                team.getCreatedAt()
        );
    }

    @Override
    public List<TeamVO> listMyTeams() {
        Long userId = SecurityUtils.getCurrentUserId();

        // 1. 查询用户所有活跃成员关系
        List<TeamMember> memberships = teamMemberMapper.selectList(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getUserId, userId)
                        .eq(TeamMember::getStatus, 1));
        if (memberships.isEmpty()) {
            return List.of();
        }

        // 2. 批量查询团队
        List<Long> teamIds = memberships.stream().map(TeamMember::getTeamId).toList();
        Map<Long, Team> teamMap = teamMapper.selectBatchIds(teamIds).stream()
                .filter(t -> t.getDeleted() == 0)
                .collect(Collectors.toMap(Team::getId, t -> t));

        // 3. 批量查询成员数
        List<Long> activeTeamIds = new ArrayList<>(teamMap.keySet());
        Map<Long, Long> memberCountMap = Map.of();
        if (!activeTeamIds.isEmpty()) {
            memberCountMap = teamMemberMapper.selectMemberCountByTeamIds(activeTeamIds).stream()
                    .collect(Collectors.toMap(
                            m -> ((Number) m.get("team_id")).longValue(),
                            m -> ((Number) m.get("cnt")).longValue()));
        }

        // 4. 组装结果
        Map<Long, Long> finalMemberCountMap = memberCountMap;
        Map<Long, TeamMemberRole> roleMap = memberships.stream()
                .collect(Collectors.toMap(TeamMember::getTeamId, TeamMember::getRole));

        return memberships.stream()
                .filter(m -> teamMap.containsKey(m.getTeamId()))
                .map(m -> {
                    Team team = teamMap.get(m.getTeamId());
                    int count = finalMemberCountMap.getOrDefault(m.getTeamId(), 0L).intValue();
                    return toTeamVO(team, count, roleMap.get(m.getTeamId()).name());
                })
                .toList();
    }

    @Override
    public TeamVO updateTeam(Long teamId, TeamUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Team team = getActiveTeam(teamId);

        // 仅创建者可更新
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null || member.getRole() != TeamMemberRole.CREATOR) {
            throw new BusinessException(ErrorCode.NOT_TEAM_CREATOR);
        }

        if (request.teamName() != null) {
            team.setTeamName(request.teamName());
        }
        if (request.teamDesc() != null) {
            team.setTeamDesc(request.teamDesc());
        }
        team.setUpdatedAt(OffsetDateTime.now());
        teamMapper.updateById(team);

        long memberCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getTeamId, teamId)
                        .eq(TeamMember::getStatus, 1));

        log.info("Team updated: id={}, updaterId={}", teamId, userId);
        return toTeamVO(team, (int) memberCount, member.getRole().name());
    }

    @Override
    public void dissolveTeam(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();

        txTemplate.executeWithoutResult(status -> {
            // SELECT FOR UPDATE 防并发
            Team team = teamMapper.selectByIdForUpdate(teamId);
            if (team == null || team.getDeleted() != 0) {
                throw new BusinessException(ErrorCode.TEAM_NOT_FOUND);
            }
            if (!userId.equals(team.getCreatorId())) {
                throw new BusinessException(ErrorCode.NOT_TEAM_CREATOR);
            }

            // 软删除团队
            team.setDeleted(1);
            team.setUpdatedAt(OffsetDateTime.now());
            teamMapper.updateById(team);

            // 批量移除所有成员
            // 批量移除所有成员
            teamMemberMapper.update(null, new LambdaUpdateWrapper<TeamMember>()
                    .eq(TeamMember::getTeamId, teamId)
                    .eq(TeamMember::getStatus, 1)
                    .set(TeamMember::getStatus, 0)
                    .set(TeamMember::getUpdatedAt, OffsetDateTime.now()));

            // TODO: Phase 4 — REJECT 所有 PENDING 审批 + 清理向量数据
            // TODO: MinIO bucket 延迟清理 — OrphanChunkCleaner 会在 bucket 为空且团队 deleted=1 后自动删除
            log.info("Team dissolved: id={}, creatorId={}", teamId, userId);
        });
    }

    @Override
    public void setCreatorQuota(Long teamId, long maxUploadMb) {
        Long userId = SecurityUtils.getCurrentUserId();
        Team team = getActiveTeam(teamId);

        // Service 层权限校验：仅创建者可设置
        if (!userId.equals(team.getCreatorId())) {
            throw new BusinessException(ErrorCode.NOT_TEAM_CREATOR);
        }

        txTemplate.executeWithoutResult(status -> {
            team.setCreatorUploadLimitMb(maxUploadMb);
            team.setUpdatedAt(OffsetDateTime.now());
            teamMapper.updateById(team);

            // 同步更新创建者成员记录的额度
            TeamMember creator = teamMemberMapper.selectByTeamAndUser(teamId, team.getCreatorId());
            if (creator != null) {
                creator.setUploadLimitMb(maxUploadMb);
                creator.setUpdatedAt(OffsetDateTime.now());
                teamMemberMapper.updateById(creator);
            }
        });

        log.info("Creator quota updated: teamId={}, newLimit={}MB", teamId, maxUploadMb);
    }

    // === 私有方法 ===

    private Team getActiveTeam(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || team.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND);
        }
        return team;
    }

    private TeamVO toTeamVO(Team team, int memberCount, String myRole) {
        return new TeamVO(
                team.getId(),
                team.getTeamName(),
                team.getTeamDesc(),
                team.getCreatorId(),
                memberCount,
                myRole,
                team.getCreatedAt()
        );
    }
}
