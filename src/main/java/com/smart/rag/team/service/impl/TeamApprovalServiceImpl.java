package com.smart.rag.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.request.PageRequest;
import com.smart.rag.common.response.PagedResult;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.security.util.SecurityUtils;
import com.smart.rag.team.config.TeamProperties;
import com.smart.rag.team.dto.ApprovalReviewRequest;
import com.smart.rag.team.dto.ApprovalVO;
import com.smart.rag.team.dto.MyApprovalVO;
import com.smart.rag.team.entity.Team;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.entity.TeamUploadApproval;
import com.smart.rag.team.enums.ApprovalStatus;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.mapper.TeamMapper;
import com.smart.rag.team.mapper.TeamMemberMapper;
import com.smart.rag.team.mapper.TeamUploadApprovalMapper;
import com.smart.rag.team.service.TeamApprovalService;
import com.smart.rag.user.entity.SysUser;
import com.smart.rag.user.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
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
    public PagedResult<ApprovalVO> listPending(Long teamId, PageRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 校验管理员/创建者权限
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null || (member.getRole() != TeamMemberRole.CREATOR && member.getRole() != TeamMemberRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_TEAM_ADMIN);
        }

        // 分页查询
        Page<TeamUploadApproval> page = approvalMapper.selectPage(req.toPage(),
                new LambdaQueryWrapper<TeamUploadApproval>()
                        .eq(TeamUploadApproval::getTeamId, teamId)
                        .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
                        .orderByAsc(TeamUploadApproval::getCreatedAt));

        if (page.getRecords().isEmpty()) return new PagedResult<>(List.of(), req.page(), req.size(), 0, 0);

        // 批量查询文档和用户
        List<Long> docIds = page.getRecords().stream().map(TeamUploadApproval::getDocumentId).distinct().toList();
        List<Long> uploaderIds = page.getRecords().stream().map(TeamUploadApproval::getUploaderId).distinct().toList();

        Map<Long, RagDocument> docMap = ragDocumentMapper.selectBatchIds(docIds).stream()
                .collect(java.util.stream.Collectors.toMap(RagDocument::getId, d -> d));
        Map<Long, SysUser> userMap = sysUserMapper.selectBatchIds(uploaderIds).stream()
                .collect(java.util.stream.Collectors.toMap(SysUser::getId, u -> u));

        return PagedResult.<TeamUploadApproval, ApprovalVO>of(page, a -> {
            RagDocument doc = docMap.get(a.getDocumentId());
            SysUser uploader = userMap.get(a.getUploaderId());
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
        });
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
            // 乐观锁：UPDATE WHERE status = PENDING，只有一个审批者能成功
            int updated = approvalMapper.update(null, new LambdaUpdateWrapper<TeamUploadApproval>()
                    .eq(TeamUploadApproval::getId, approvalId)
                    .eq(TeamUploadApproval::getTeamId, teamId)
                    .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
                    .set(TeamUploadApproval::getStatus, isApprove ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED)
                    .set(TeamUploadApproval::getReviewerId, reviewerId)
                    .set(TeamUploadApproval::getReviewComment, request.comment())
                    .set(TeamUploadApproval::getReviewedAt, OffsetDateTime.now()));
            if (updated == 0) {
                throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
            }

            // 更新文档状态
            TeamUploadApproval approval = approvalMapper.selectById(approvalId);
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
    public PagedResult<MyApprovalVO> listMyApprovals(Long teamId, PageRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        getActiveTeam(teamId);

        // 必须是成员
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER);
        }

        // 分页查询
        Page<TeamUploadApproval> page = approvalMapper.selectPage(req.toPage(),
                new LambdaQueryWrapper<TeamUploadApproval>()
                        .eq(TeamUploadApproval::getTeamId, teamId)
                        .eq(TeamUploadApproval::getUploaderId, userId)
                        .orderByDesc(TeamUploadApproval::getCreatedAt));

        if (page.getRecords().isEmpty()) return new PagedResult<>(List.of(), req.page(), req.size(), 0, 0);

        // 批量查询文档
        List<Long> docIds = page.getRecords().stream().map(TeamUploadApproval::getDocumentId).distinct().toList();
        Map<Long, RagDocument> docMap = ragDocumentMapper.selectBatchIds(docIds).stream()
                .collect(java.util.stream.Collectors.toMap(RagDocument::getId, d -> d));

        return PagedResult.<TeamUploadApproval, MyApprovalVO>of(page, a -> {
            RagDocument doc = docMap.get(a.getDocumentId());
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
        });
    }

    @Override
    public int rejectTimedOut() {
        OffsetDateTime deadline = OffsetDateTime.now().minusDays(teamProperties.getApprovalTimeoutDays());
        OffsetDateTime now = OffsetDateTime.now();

        // 查询超时的审批 ID 和文档 ID
        List<TeamUploadApproval> timedOut = approvalMapper.selectList(
                new LambdaQueryWrapper<TeamUploadApproval>()
                        .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
                        .lt(TeamUploadApproval::getCreatedAt, deadline)
                        .select(TeamUploadApproval::getId, TeamUploadApproval::getDocumentId));

        if (timedOut.isEmpty()) return 0;

        List<Long> approvalIds = timedOut.stream().map(TeamUploadApproval::getId).toList();
        List<Long> docIds = timedOut.stream().map(TeamUploadApproval::getDocumentId).distinct().toList();

        txTemplate.executeWithoutResult(status -> {
            // 批量更新审批记录 — WHERE status = PENDING 防覆盖人工审批结果
            approvalMapper.update(null, new LambdaUpdateWrapper<TeamUploadApproval>()
                    .in(TeamUploadApproval::getId, approvalIds)
                    .eq(TeamUploadApproval::getStatus, ApprovalStatus.PENDING)
                    .set(TeamUploadApproval::getStatus, ApprovalStatus.REJECTED)
                    .set(TeamUploadApproval::getReviewComment, "审批超时，系统自动拒绝")
                    .set(TeamUploadApproval::getReviewedAt, now));

            // 批量更新文档状态 — WHERE status = PENDING_APPROVAL 确保不覆盖已处理的文档
            ragDocumentMapper.update(null, new LambdaUpdateWrapper<RagDocument>()
                    .in(RagDocument::getId, docIds)
                    .eq(RagDocument::getStatus, EtlStatus.PENDING_APPROVAL)
                    .set(RagDocument::getStatus, EtlStatus.REJECTED));
        });

        if (!timedOut.isEmpty()) {
            log.info("Rejected {} timed-out approvals", timedOut.size());
        }
        return timedOut.size();
    }

    /**
     * 审批通过后触发 ETL（内部方法，不应被外部直接调用）
     */
    private void approveAndTriggerEtl(Long approvalId) {
        TeamUploadApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            log.warn("approveAndTriggerEtl: approval not found, id={}", approvalId);
            return;
        }

        RagDocument doc = ragDocumentMapper.selectById(approval.getDocumentId());
        if (doc == null) {
            log.warn("approveAndTriggerEtl: document not found, docId={}", approval.getDocumentId());
            return;
        }
        if (doc.getStatus() != EtlStatus.PROCESSING) {
            log.warn("approveAndTriggerEtl: document not in PROCESSING state, docId={}, status={}", doc.getId(), doc.getStatus());
            return;
        }

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
