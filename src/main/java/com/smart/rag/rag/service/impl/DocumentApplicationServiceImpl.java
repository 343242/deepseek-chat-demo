package com.smart.rag.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.DocumentApplicationService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.upload.UploadStrategyRouter;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 文档应用服务实现
 * <p>
 * 职责：编排文档的前端操作（上传 → 查询 → 删除 → 重试）。
 * <p>
 * 上传链路委托给 {@link UploadStrategyRouter}（策略模式），
 * 删除的级联清理委托给 {@link DocumentLifecycleService}。
 * 权限校验委托给 {@link TeamAccessGate}（团队文档）
 * 和简单的 userId 比对（个人文档）。
 */
@Service
public class DocumentApplicationServiceImpl implements DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationServiceImpl.class);

    // R1-H2: list size 上限复用 PageRequest.MAX_PAGE_SIZE（W3 LOW-2 统一，避免 drift）

    private final EtlDispatchService etlDispatchService;
    private final RagDocumentMapper ragDocumentMapper;
    private final DocumentLifecycleService documentLifecycleService;
    private final UploadStrategyRouter uploadStrategyRouter;
    private final TeamAccessGate teamAccessGate;
    private final VectorStoreMapper vectorStoreMapper;

    public DocumentApplicationServiceImpl(EtlDispatchService etlDispatchService,
                                          RagDocumentMapper ragDocumentMapper,
                                          DocumentLifecycleService documentLifecycleService,
                                          UploadStrategyRouter uploadStrategyRouter,
                                          TeamAccessGate teamAccessGate,
                                          VectorStoreMapper vectorStoreMapper) {
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.documentLifecycleService = documentLifecycleService;
        this.uploadStrategyRouter = uploadStrategyRouter;
        this.teamAccessGate = teamAccessGate;
        this.vectorStoreMapper = vectorStoreMapper;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file) {
        return upload(file, null);
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        verifyTeamAccess(teamId, currentUserId);
        return uploadStrategyRouter.route(teamId).upload(file, teamId, null, currentUserId);
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, @Nullable Long replaceDocumentId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        verifyTeamAccess(teamId, currentUserId);
        return uploadStrategyRouter.route(teamId).upload(file, teamId, replaceDocumentId, currentUserId);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files) {
        return uploadBatch(files.toArray(new MultipartFile[0]), null);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(MultipartFile[] files, @Nullable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        verifyTeamAccess(teamId, currentUserId);
        return uploadStrategyRouter.route(teamId).uploadBatch(Arrays.asList(files), teamId, null, currentUserId);
    }

    @Override
    public PagedResult<DocumentDTO> listAll(int page, int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        int[] normalized = normalizePaging(page, size);
        Page<RagDocument> mpPage = new Page<>(normalized[0], normalized[1]);
        LambdaQueryWrapper<RagDocument> wrapper = new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getUserId, currentUserId)
                .isNull(RagDocument::getTeamId)
                .ne(RagDocument::getStatus, EtlStatus.SUPERSEDED)
                .orderByDesc(RagDocument::getCreateTime);
        Page<RagDocument> result = ragDocumentMapper.selectPage(mpPage, wrapper);
        return PagedResult.of(result, this::toDTO);
    }

    @Override
    public PagedResult<DocumentDTO> listByTeam(Long teamId, int page, int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        TeamAccessGate.TeamAccess access = teamAccessGate.verifyAccess(teamId, currentUserId);

        int[] normalized = normalizePaging(page, size);
        Page<RagDocument> mpPage = new Page<>(normalized[0], normalized[1]);
        LambdaQueryWrapper<RagDocument> wrapper = new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getTeamId, teamId)
                .ne(RagDocument::getStatus, EtlStatus.SUPERSEDED)
                .orderByDesc(RagDocument::getCreateTime);

        // R1-M1 可见性分层（方案 B）：非 ADMIN/CREATOR 成员只看到
        // （自己上传的任意状态）OR（全队 COMPLETED）；管理员/创建者看全队全部
        if (!access.manager()) {
            wrapper.and(w -> w.eq(RagDocument::getUserId, currentUserId)
                    .or().eq(RagDocument::getStatus, EtlStatus.COMPLETED));
        }

        Page<RagDocument> result = ragDocumentMapper.selectPage(mpPage, wrapper);
        return PagedResult.of(result, this::toDTO);
    }

    @Override
    public DocumentDTO getById(Long id) {
        RagDocument doc = verifyAccess(id);
        return toDTO(doc);
    }

    @Override
    public boolean delete(Long id) {
        RagDocument doc = verifyAccess(id);
        assertCanMutate(doc);
        return documentLifecycleService.cascadeDelete(doc);
    }

    @Override
    public DocumentUploadResponse retry(Long id) {
        RagDocument doc = verifyAccess(id);
        assertCanMutate(doc);
        if (doc.getSupersededBy() != null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "文档已被新版本替代，无法重试");
        }
        if (doc.getStatus() != EtlStatus.FAILED && doc.getStatus() != EtlStatus.VECTOR_FAILED) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
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

    /**
     * 获取文档版本历史
     * <p>
     * R1-H2 评估：不加分页。结果集天然有界——按 documentGroupId 聚合的版本链，
     * 单组版本数受 {@code DocumentSupersedeService.MAX_VERSION_RETRY} (3) + 业务替换频率约束，
     * 实际 rarely 超过个位数。加分会无谓改动 chat-history API 契约。
     * 若未来出现「文档组爆炸」（如自动版本化），再行分页。
     */
    @Override
    public List<DocumentDTO> getHistory(Long id) {
        RagDocument doc = verifyAccess(id);
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

    @Override
    public PagedResult<ChunkDTO> listChunks(Long documentId, int page, int size) {
        // 复用文档归属校验（个人文档 owner / 团队文档成员 + R1-M1 可见性分层）
        verifyAccess(documentId);
        int[] normalized = normalizePaging(page, size);
        int p = normalized[0];
        int s = normalized[1];
        String docIdStr = String.valueOf(documentId);
        int offset = (p - 1) * s;
        List<VectorStoreMapper.VectorStoreRow> rows =
                vectorStoreMapper.selectChunksByDocumentIdPaged(docIdStr, offset, s);
        long total = vectorStoreMapper.countChunksByDocumentId(docIdStr);
        List<ChunkDTO> content = rows.stream().map(this::toChunkDTO).toList();
        int totalPages = s > 0 ? (int) ((total + s - 1) / s) : 0;
        return new PagedResult<>(content, p, s, total, totalPages);
    }

    @Override
    public ChunkDTO getChunk(String chunkId) {
        VectorStoreMapper.VectorStoreRow row = vectorStoreMapper.selectChunkById(chunkId);
        if (row == null) {
            throw new ServiceException(ServiceErrorCode.NOT_FOUND, "片段不存在: " + chunkId);
        }
        Long documentId = parseDocumentId(row.metadata());
        if (documentId == null) {
            // 脏数据防御：无 documentId 的 chunk 拒绝访问，不泄露存在性
            log.warn("Chunk {} has no parseable documentId in metadata, denying access", chunkId);
            throw new ServiceException(ServiceErrorCode.NOT_FOUND, "片段不存在: " + chunkId);
        }
        // 复用文档归属校验：个人文档需 owner；团队文档需成员（R1-M1 可见性分层）
        verifyAccess(documentId);
        return toChunkDTO(row);
    }

    // === 私有方法 ===

    /**
     * 校验团队上传权限（teamId != null 时）
     */
    private void verifyTeamAccess(@Nullable Long teamId, Long userId) {
        if (teamId != null) {
            teamAccessGate.verifyAccess(teamId, userId);
        }
    }

    /**
     * R1-H2: 归一化分页参数：page &lt; 1 → 1；size 钳制到 [1, {@value PageRequest#MAX_PAGE_SIZE}]。
     * 超过上限时静默钳制并记录 debug 日志（按 PRD 决策偏好 clamp）。
     *
     * @return [page, size]
     */
    private int[] normalizePaging(int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 1;
        }
        if (size > PageRequest.MAX_PAGE_SIZE) {
            log.debug("list page size {} clamped to {}", size, PageRequest.MAX_PAGE_SIZE);
            size = PageRequest.MAX_PAGE_SIZE;
        }
        return new int[]{page, size};
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
    private RagDocument verifyAccess(Long id) {
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
    private void assertCanMutate(RagDocument doc) {
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

    private ChunkDTO toChunkDTO(VectorStoreMapper.VectorStoreRow row) {
        Map<String, Object> metadata = row.metadata() != null ? row.metadata() : Map.of();
        Long documentId = parseDocumentId(metadata);
        String fileName = metadata.get("fileName") instanceof String s ? s : null;
        return new ChunkDTO(row.id(), row.content(), documentId, fileName, metadata);
    }

    /**
     * 从 metadata 解析 documentId（Long）。metadata 中 documentId 存为字符串。
     *
     * @return documentId；缺失或不可解析返回 null
     */
    private static Long parseDocumentId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get("documentId");
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
