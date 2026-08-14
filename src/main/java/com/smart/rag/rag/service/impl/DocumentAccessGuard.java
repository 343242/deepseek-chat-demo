package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.TeamAccessGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文档访问/变更权限守卫（单一职责）
 * <p>
 * 从 DocumentApplicationServiceImpl 中提取，符合 SRP。
 */
@Component
public class DocumentAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(DocumentAccessGuard.class);

    private final RagDocumentMapper ragDocumentMapper;
    private final TeamAccessGate teamAccessGate;

    public DocumentAccessGuard(RagDocumentMapper ragDocumentMapper, TeamAccessGate teamAccessGate) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.teamAccessGate = teamAccessGate;
    }

    /**
     * 统一文档访问权限校验
     * <p>
     * 个人文档：userId 匹配
     * 团队文档：团队成员 + (管理员/创建者 或 上传者)
     * <p>
     * R1-H4: 不存在时抛 {@link ServiceErrorCode#DOCUMENT_NOT_FOUND}（→ 204001），
     * 无权访问时抛 {@link ClientErrorCode#FORBIDDEN}（→ 100004）。
     * 调用方无需再做 null 判断。
     */
    public RagDocument verifyAccess(Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException(ServiceErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + id);
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();

        if (doc.getTeamId() == null) {
            // 个人文档
            if (!currentUserId.equals(doc.getUserId())) {
                log.warn("Access denied: userId={} attempted to access personal document id={}", currentUserId, id);
                throw new ClientException(ClientErrorCode.FORBIDDEN, "无权操作该文档");
            }
        } else {
            // 团队文档 — 必须是成员
            TeamAccessGate.TeamAccess access = teamAccessGate.verifyAccess(doc.getTeamId(), currentUserId);
            // R1-M1 可见性分层（方案 B）：非 owner/ADMIN/CREATOR 只能访问 COMPLETED 文档；
            // 中间态/失败/被替代等对其他成员不可见，返回 NOT_FOUND（不泄露存在性）。
            if (!isOwnerOrManager(doc.getUserId(), currentUserId, access)
                    && doc.getStatus() != EtlStatus.COMPLETED) {
                log.warn("Visibility denied (R1-M1): userId={} accessed non-completed team doc id={} status={}",
                        currentUserId, id, doc.getStatus());
                throw new ServiceException(ServiceErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + id);
            }
        }

        return doc;
    }

    /**
     * R1-M1: 团队文档变更权限（delete / retry）——仅文档所有者或团队管理员/创建者。
     * <p>
     * 个人文档已由 {@link #verifyAccess} 保证 owner。读（getById/getHistory）由
     * verifyAccess 的可见性分层管控：COMPLETED 全队可读，其余仅 owner/管理员可见。
     */
    public void assertCanMutate(RagDocument doc) {
        if (doc.getTeamId() == null) {
            return; // 个人文档：verifyAccess 已校验 owner
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        TeamAccessGate.TeamAccess access = teamAccessGate.verifyAccess(doc.getTeamId(), currentUserId);
        if (!isOwnerOrManager(doc.getUserId(), currentUserId, access)) {
            log.warn("Mutation denied (R1-M1): userId={} attempted to mutate team doc id={} owned by {}",
                    currentUserId, doc.getId(), doc.getUserId());
            throw new ServiceException(ServiceErrorCode.DOCUMENT_OWNERSHIP_DENIED,
                    "仅文档所有者或团队管理员可操作该文档");
        }
    }

    /** 文档所有者本人 或 团队 ADMIN / CREATOR */
    private static boolean isOwnerOrManager(Long ownerId, Long currentUserId, TeamAccessGate.TeamAccess access) {
        return currentUserId.equals(ownerId) || access.manager();
    }
}
