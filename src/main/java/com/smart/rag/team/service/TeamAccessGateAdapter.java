package com.smart.rag.team.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.team.entity.TeamMember;
import com.smart.rag.team.entity.TeamUploadApproval;
import com.smart.rag.team.enums.ApprovalStatus;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.mapper.TeamMemberMapper;
import com.smart.rag.team.mapper.TeamUploadApprovalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * {@link TeamAccessGate} 的 team 侧适配实现。
 * <p>
 * 桥接 rag 文档域端口与 team 模块内部能力（{@link TeamMembershipVerifier} /
 * {@link TeamStatusService}），将 team 的角色枚举收敛为 rag 所需的「是否管理者」布尔，
 * 使 rag 无需反向依赖 team。
 * <p>
 * 上传额度与审批记录为 presigned 直传路径新增的端口能力，语义对齐
 * {@code TeamUploadStrategy}（同源维护，勿单边漂移）。
 */
@Component
public class TeamAccessGateAdapter implements TeamAccessGate {

    private static final Logger log = LoggerFactory.getLogger(TeamAccessGateAdapter.class);

    private final TeamMembershipVerifier teamMembershipVerifier;
    private final TeamStatusService teamStatusService;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamUploadApprovalMapper approvalMapper;
    private final RagDocumentMapper ragDocumentMapper;

    public TeamAccessGateAdapter(TeamMembershipVerifier teamMembershipVerifier,
                                 TeamStatusService teamStatusService,
                                 TeamMemberMapper teamMemberMapper,
                                 TeamUploadApprovalMapper approvalMapper,
                                 RagDocumentMapper ragDocumentMapper) {
        this.teamMembershipVerifier = teamMembershipVerifier;
        this.teamStatusService = teamStatusService;
        this.teamMemberMapper = teamMemberMapper;
        this.approvalMapper = approvalMapper;
        this.ragDocumentMapper = ragDocumentMapper;
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

    @Override
    public void verifyUploadQuota(Long teamId, Long userId, long extraSize) {
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null) {
            // 与 TeamUploadStrategy 同语义：非成员由 verifyAccess 拦截，这里兜底拒绝
            throw new ClientException(ClientErrorCode.FORBIDDEN, "不是团队成员");
        }

        long limitBytes = member.getUploadLimitMb() * 1024L * 1024L;
        Long totalBytes = ragDocumentMapper.selectFileSizeSum(teamId, userId, EtlStatus.REJECTED.getCode());
        long usedBytes = totalBytes != null ? totalBytes : 0L;

        if (usedBytes + extraSize > limitBytes) {
            log.warn("Upload quota exceeded: teamId={}, userId={}, used={}MB, limit={}MB, requested={}MB",
                    teamId, userId, usedBytes / 1024 / 1024, member.getUploadLimitMb(), extraSize / 1024 / 1024);
            throw new ClientException(ClientErrorCode.UPLOAD_QUOTA_EXCEEDED);
        }
    }

    @Override
    public void createUploadApproval(Long teamId, Long documentId, Long uploaderId) {
        TeamUploadApproval approval = new TeamUploadApproval();
        approval.setTeamId(teamId);
        approval.setDocumentId(documentId);
        approval.setUploaderId(uploaderId);
        approval.setStatus(ApprovalStatus.PENDING);
        approval.setCreatedAt(OffsetDateTime.now());
        approvalMapper.insert(approval);
        log.info("Approval created: teamId={}, documentId={}, uploaderId={}", teamId, documentId, uploaderId);
    }
}
