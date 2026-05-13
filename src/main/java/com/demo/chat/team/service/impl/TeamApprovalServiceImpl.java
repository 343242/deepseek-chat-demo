package com.demo.chat.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.security.util.SecurityUtils;
import com.demo.chat.team.config.TeamProperties;
import com.demo.chat.team.dto.ApprovalReviewRequest;
import com.demo.chat.team.dto.ApprovalVO;
import com.demo.chat.team.dto.MyApprovalVO;
import com.demo.chat.team.entity.Team;
import com.demo.chat.team.entity.TeamMember;
import com.demo.chat.team.entity.TeamUploadApproval;
import com.demo.chat.team.enums.ApprovalStatus;
import com.demo.chat.team.enums.TeamMemberRole;
import com.demo.chat.team.mapper.TeamMapper;
import com.demo.chat.team.mapper.TeamMemberMapper;
import com.demo.chat.team.mapper.TeamUploadApprovalMapper;
import com.demo.chat.team.service.TeamApprovalService;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 团队上传审批服务实现
 */
@Service
public class TeamApprovalServiceImpl implements TeamApprovalService {

    private static final Logger log = LoggerFactory.getLogger(TeamApprovalServiceImpl.class);

    private final TeamUploadApprovalMapper approvalMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final RagDocumentMapper ragDocumentMapper;
    private final SysUserMapper sysUserMapper;
    private final EtlDispatchService etlDispatchService;
    private final TeamProperties teamProperties;
    private final TransactionTemplate txTemplate;

    public TeamApprovalServiceImpl(TeamUploadApprovalMapper approvalMapper,
                                    TeamMapper teamMapper,
                                    TeamMemberMapper teamMemberMapper,
                                    RagDocumentMapper ragDocumentMapper,
                                    SysUserMapper sysUserMapper,
                                    EtlDispatchService etlDispatchService,
                                    TeamProperties teamProperties,
                                    TransactionTemplate txTemplate) {
        this.approvalMapper = approvalMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.ragDocumentMapper = ragDocumentMapper;
        this.sysUserMapper = sysUserMapper;
        this.etlDispatchService = etlDispatchService;
        this.teamProperties = teamProperties;
        this.txTemplate = txTemplate;
    }

    @Override
    public List<ApprovalVO> listPending(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 校验管理员/创建者权限
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null || (member.getRole() != TeamMemberRole.CREATOR && member.getRole() != TeamMemberRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }

        List<TeamUploadApproval> approvals = approvalMapper.selectList(
                new LambdaQueryWrapper<TeamUploadApproval>()
                        .eq(TeamUploadApproval::getTeamId, teamId)
                        .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
                        .orderByAsc(TeamUploadApproval::getCreatedAt));

        return approvals.stream().map(a -> {
            RagDocument doc = ragDocumentMapper.selectById(a.getDocumentId());
            SysUser uploader = sysUserMapper.selectActiveById(a.getUploaderId()).orElse(null);
            return new ApprovalVO(
                    a.getId(),
                    a.getDocumentId(),
                    doc != null ? doc.getFileName() : "未知",
                    doc != null ? doc.getFileSize() : 0,
                    a.getUploaderId(),
                    uploader != null ? uploader.getNickname() : "未知",
                    a.getStatus().name(),
                    a.getReviewerId(),
                    a.getReviewComment(),
                    a.getCreatedAt(),
                    a.getReviewedAt()
            );
        }).toList();
    }

    @Override
    public void review(Long teamId, Long approvalId, ApprovalReviewRequest request) {
        Long reviewerId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 校验管理员/创建者权限
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, reviewerId);
        if (member == null || (member.getRole() != TeamMemberRole.CREATOR && member.getRole() != TeamMemberRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }

        boolean isApprove = "APPROVE".equals(request.action());

        txTemplate.executeWithoutResult(status -> {
            // 事务内查询 + 状态检查 + 更新，防并发
            TeamUploadApproval approval = approvalMapper.selectById(approvalId);
            if (approval == null || !approval.getTeamId().equals(teamId)) {
                throw new BusinessException(ErrorCode.APPROVAL_NOT_FOUND);
            }
            if (approval.getStatus() != ApprovalStatus.PENDING) {
                throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
            }

            // 更新审批记录
            approval.setStatus(isApprove ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
            approval.setReviewerId(reviewerId);
            approval.setReviewComment(request.comment());
            approval.setReviewedAt(OffsetDateTime.now());
            approvalMapper.updateById(approval);

            // 更新文档状态
            RagDocument doc = ragDocumentMapper.selectById(approval.getDocumentId());
            if (doc != null) {
                doc.setStatus(isApprove ? EtlStatus.PROCESSING : EtlStatus.REJECTED);
                ragDocumentMapper.updateById(doc);
            }

            log.info("Approval reviewed: id={}, action={}, reviewerId={}", approvalId, request.action(), reviewerId);
        });

        // 审批通过后触发 ETL（事务外执行，避免长事务）
        if (isApprove) {
            approveAndTriggerEtl(approvalId);
        }
    }

    @Override
    public List<MyApprovalVO> listMyApprovals(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 必须是成员
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        List<TeamUploadApproval> approvals = approvalMapper.selectList(
                new LambdaQueryWrapper<TeamUploadApproval>()
                        .eq(TeamUploadApproval::getTeamId, teamId)
                        .eq(TeamUploadApproval::getUploaderId, userId)
                        .orderByDesc(TeamUploadApproval::getCreatedAt));

        return approvals.stream().map(a -> {
            RagDocument doc = ragDocumentMapper.selectById(a.getDocumentId());
            return new MyApprovalVO(
                    a.getId(),
                    a.getDocumentId(),
                    doc != null ? doc.getFileName() : "未知",
                    a.getStatus().name(),
                    a.getReviewerId(),
                    a.getReviewComment(),
                    a.getCreatedAt(),
                    a.getReviewedAt()
            );
        }).toList();
    }

    @Override
    public int rejectTimedOut() {
        OffsetDateTime deadline = OffsetDateTime.now().minusDays(teamProperties.getApprovalTimeoutDays());

        List<TeamUploadApproval> timedOut = approvalMapper.selectList(
                new LambdaQueryWrapper<TeamUploadApproval>()
                        .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
                        .lt(TeamUploadApproval::getCreatedAt, deadline));

        for (TeamUploadApproval approval : timedOut) {
            txTemplate.executeWithoutResult(status -> {
                approval.setStatus(ApprovalStatus.REJECTED);
                approval.setReviewComment("审批超时，系统自动拒绝");
                approval.setReviewedAt(OffsetDateTime.now());
                approvalMapper.updateById(approval);

                RagDocument doc = ragDocumentMapper.selectById(approval.getDocumentId());
                if (doc != null) {
                    doc.setStatus(EtlStatus.REJECTED);
                    ragDocumentMapper.updateById(doc);
                }
            });
        }

        if (!timedOut.isEmpty()) {
            log.info("Rejected {} timed-out approvals", timedOut.size());
        }
        return timedOut.size();
    }

    @Override
    public void approveAndTriggerEtl(Long approvalId) {
        TeamUploadApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) return;

        RagDocument doc = ragDocumentMapper.selectById(approval.getDocumentId());
        if (doc == null || doc.getStatus() != EtlStatus.PROCESSING) return;

        etlDispatchService.dispatchAsync(doc.getId(), doc.getBucket(), doc.getStorageKey(),
                doc.getFileName(), doc.getMimeType(), doc.getFileSize(), doc.getUserId(), doc.getTeamId());

        log.info("ETL triggered after approval: documentId={}, approvalId={}", doc.getId(), approvalId);
    }

    // === 私有方法 ===

    private Team getActiveTeam(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || team.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND);
        }
        return team;
    }
}
