package com.demo.chat.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.DocumentApplicationService;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.security.util.SecurityUtils;
import com.demo.chat.team.service.TeamMembershipVerifier;
import com.demo.chat.team.upload.UploadStrategyFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

/**
 * 文档应用服务实现
 * <p>
 * 职责：编排文档的前端操作（上传 → 查询 → 删除 → 重试）。
 * <p>
 * 上传链路委托给 {@link UploadStrategyFactory}（策略模式），
 * 删除的级联清理委托给 {@link DocumentLifecycleService}。
 * 权限校验委托给 {@link TeamMembershipVerifier}（团队文档）
 * 和简单的 userId 比对（个人文档）。
 */
@Service
public class DocumentApplicationServiceImpl implements DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationServiceImpl.class);

    private final EtlDispatchService etlDispatchService;
    private final RagDocumentMapper ragDocumentMapper;
    private final DocumentLifecycleService documentLifecycleService;
    private final UploadStrategyFactory uploadStrategyFactory;
    private final TeamMembershipVerifier teamMembershipVerifier;

    public DocumentApplicationServiceImpl(EtlDispatchService etlDispatchService,
                                          RagDocumentMapper ragDocumentMapper,
                                          DocumentLifecycleService documentLifecycleService,
                                          UploadStrategyFactory uploadStrategyFactory,
                                          TeamMembershipVerifier teamMembershipVerifier) {
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.documentLifecycleService = documentLifecycleService;
        this.uploadStrategyFactory = uploadStrategyFactory;
        this.teamMembershipVerifier = teamMembershipVerifier;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file) {
        return upload(file, null);
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        verifyTeamAccess(teamId, currentUserId);
        return uploadStrategyFactory.route(teamId).upload(file, teamId, null, currentUserId);
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        verifyTeamAccess(teamId, currentUserId);
        return uploadStrategyFactory.route(teamId).upload(file, teamId, replaceDocumentId, currentUserId);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files) {
        return uploadBatch(files.toArray(new MultipartFile[0]), null);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(MultipartFile[] files, @Nullable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        verifyTeamAccess(teamId, currentUserId);
        return uploadStrategyFactory.route(teamId).uploadBatch(Arrays.asList(files), teamId, null, currentUserId);
    }

    @Override
    public List<DocumentDTO> listAll() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<RagDocument> docs = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getUserId, currentUserId)
                        .isNull(RagDocument::getTeamId)
                        .ne(RagDocument::getStatus, EtlStatus.SUPERSEDED)
                        .orderByDesc(RagDocument::getCreateTime));
        return docs.stream().map(this::toDTO).toList();
    }

    @Override
    public List<DocumentDTO> listByTeam(Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 校验团队成员身份
        teamMembershipVerifier.verifyMember(teamId, currentUserId);

        List<RagDocument> docs = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getTeamId, teamId)
                        .ne(RagDocument::getStatus, EtlStatus.SUPERSEDED)
                        .orderByDesc(RagDocument::getCreateTime));
        return docs.stream().map(this::toDTO).toList();
    }

    @Override
    public DocumentDTO getById(Long id) {
        RagDocument doc = verifyAccess(id);
        return doc != null ? toDTO(doc) : null;
    }

    @Override
    public boolean delete(Long id) {
        RagDocument doc = verifyAccess(id);
        if (doc == null) {
            return false;
        }
        return documentLifecycleService.cascadeDelete(doc);
    }

    @Override
    public DocumentUploadResponse retry(Long id) {
        RagDocument doc = verifyAccess(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + id);
        }
        if (doc.getSupersededBy() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档已被新版本替代，无法重试");
        }
        if (doc.getStatus() != EtlStatus.FAILED && doc.getStatus() != EtlStatus.VECTOR_FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "仅 FAILED / VECTOR_FAILED 状态的文档可以重试，当前状态: " + doc.getStatus());
        }

        log.info("Retrying ETL for document: id={}, file={}, status={}", id, doc.getFileName(), doc.getStatus());

        try {
            etlDispatchService.deleteVectors(id);
        } catch (Exception e) {
            log.warn("Failed to clean old vectors for retry doc={}: {}", id, e.getMessage());
        }

        etlDispatchService.dispatchAsync(id, doc.getBucket(), doc.getStorageKey(),
                doc.getFileName(), doc.getMimeType(), doc.getFileSize(), doc.getUserId(), doc.getTeamId());

        return new DocumentUploadResponse(id, doc.getFileName(), EtlStatus.PROCESSING);
    }

    @Override
    public List<DocumentDTO> getHistory(Long id) {
        RagDocument doc = verifyAccess(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + id);
        }
        String groupId = doc.getDocumentGroupId();
        if (groupId == null) {
            return List.of(toDTO(doc));
        }
        return ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getDocumentGroupId, groupId)
                        .orderByDesc(RagDocument::getVersion)
        ).stream().map(this::toDTO).toList();
    }

    // === 私有方法 ===

    /**
     * 校验团队上传权限（teamId != null 时）
     */
    private void verifyTeamAccess(@Nullable Long teamId, Long userId) {
        if (teamId != null) {
            teamMembershipVerifier.verifyMember(teamId, userId);
        }
    }

    /**
     * 统一文档访问权限校验
     * <p>
     * 个人文档：userId 匹配
     * 团队文档：团队成员 + (管理员/创建者 或 上传者)
     */
    private RagDocument verifyAccess(Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return null;
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();

        if (doc.getTeamId() == null) {
            // 个人文档
            if (!currentUserId.equals(doc.getUserId())) {
                log.warn("Access denied: userId={} attempted to access personal document id={}", currentUserId, id);
                return null;
            }
        } else {
            // 团队文档 — 必须是成员
            var member = teamMembershipVerifier.verifyMember(doc.getTeamId(), currentUserId);
            // TODO: Phase 4 — 按角色进一步细分权限
        }

        return doc;
    }

    private DocumentDTO toDTO(RagDocument doc) {
        return new DocumentDTO(
                doc.getId(),
                doc.getFileName(),
                doc.getFileSize(),
                doc.getMimeType(),
                doc.getChunkCount(),
                doc.getStatus(),
                doc.getErrorMessage(),
                doc.getUserId(),
                doc.getTeamId(),
                doc.getVersion(),
                doc.getSupersededBy(),
                doc.getDocumentGroupId(),
                doc.getCreateTime()
        );
    }
}
