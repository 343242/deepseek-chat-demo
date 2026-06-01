package com.smart.rag.team.security;

import com.smart.rag.infrastructure.exception.errorcode.ErrorCode;
import com.smart.rag.infrastructure.exception.BusinessException;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.team.service.TeamMembershipVerifier;
import com.smart.rag.team.enums.TeamMemberRole;
import com.smart.rag.team.entity.TeamMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一文档操作权限校验组件
 * <p>
 * 设计意图：跨越 team 和 rag 模块的权限校验门面。
 * 当前放在 team/security 包下，因为它依赖 TeamMembershipVerifier 和 TeamMemberRole，
 * 核心职责是判断用户对文档的操作权限（个人文档 vs 团队文档）。
 * 未来如果 rag 模块独立化，应考虑将此类提取为独立的权限模块。
 * <p>
 * 替代 {@code findAndVerifyOwner()}，统一处理个人文档和团队文档的权限判断。
 * 适用于 getById / delete / retry 等所有需要文档权限校验的场景。
 * <p>
 * 权限规则：
 * <ul>
 *   <li>个人文档（teamId=null）：userId == doc.userId</li>
 *   <li>团队文档（teamId≠null）：团队成员 + (CREATOR/ADMIN 或 文档上传者)</li>
 * </ul>
 */
@Component
public class DocumentOwnershipChecker {

    private static final Logger log = LoggerFactory.getLogger(DocumentOwnershipChecker.class);

    private final RagDocumentMapper ragDocumentMapper;
    private final TeamMembershipVerifier teamMembershipVerifier;

    public DocumentOwnershipChecker(RagDocumentMapper ragDocumentMapper,
                                    TeamMembershipVerifier teamMembershipVerifier) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.teamMembershipVerifier = teamMembershipVerifier;
    }

    /**
     * 校验文档操作权限（getById / delete / retry 通用）
     *
     * @param documentId 文档 ID
     * @param userId     当前用户 ID
     * @return 文档实体（校验通过）
     * @throws BusinessException 权限不足或文档不存在
     */
    public RagDocument checkOwnership(Long documentId, Long userId) {
        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        if (doc.getTeamId() == null) {
            // 个人文档：只有文档所有者有权限
            if (!userId.equals(doc.getUserId())) {
                throw new BusinessException(ErrorCode.DOCUMENT_OWNERSHIP_DENIED);
            }
        } else {
            // 团队文档：团队成员 + (CREATOR/ADMIN 或 文档上传者)
            TeamMember member = teamMembershipVerifier.verifyMember(doc.getTeamId(), userId);
            boolean isManager = member.getRole() == TeamMemberRole.CREATOR
                    || member.getRole() == TeamMemberRole.ADMIN;
            boolean isUploader = userId.equals(doc.getUserId());
            if (!isManager && !isUploader) {
                throw new BusinessException(ErrorCode.NO_PERMISSION_DELETE_TEAM_DOC);
            }
        }

        log.debug("Document ownership verified: docId={}, userId={}, teamId={}", documentId, userId, doc.getTeamId());
        return doc;
    }
}
