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
import com.smart.rag.rag.dto.DocumentDeleteResult;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.config.DocumentProperties;
import com.smart.rag.rag.service.AuthorizedDocumentFile;
import com.smart.rag.rag.service.DocumentApplicationService;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.rag.upload.UploadStrategyRouter;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private final DocumentDtoMapper dtoMapper;
    private final DocumentAccessGuard accessGuard;
    private final DocumentProperties documentProperties;

    public DocumentApplicationServiceImpl(EtlDispatchService etlDispatchService,
                                          RagDocumentMapper ragDocumentMapper,
                                          DocumentLifecycleService documentLifecycleService,
                                          UploadStrategyRouter uploadStrategyRouter,
                                          TeamAccessGate teamAccessGate,
                                          VectorStoreMapper vectorStoreMapper,
                                          DocumentDtoMapper dtoMapper,
                                          DocumentAccessGuard accessGuard,
                                          DocumentProperties documentProperties) {
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.documentLifecycleService = documentLifecycleService;
        this.uploadStrategyRouter = uploadStrategyRouter;
        this.teamAccessGate = teamAccessGate;
        this.vectorStoreMapper = vectorStoreMapper;
        this.dtoMapper = dtoMapper;
        this.accessGuard = accessGuard;
        this.documentProperties = documentProperties;
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
        verifyBatchLimits(files);
        return uploadStrategyRouter.route(teamId).uploadBatch(Arrays.asList(files), teamId, null, currentUserId);
    }

    /**
     * 批量上传入口级约束：文件数与总大小上限在路由到策略前统一校验，
     * 个人与团队两条路径（同一端点经 UploadStrategyRouter 分流）共享同一份限制。
     */
    private void verifyBatchLimits(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new ClientException(ClientErrorCode.UPLOAD_LIST_EMPTY);
        }
        if (files.length > documentProperties.getMaxBatchFiles()) {
            throw new ClientException(ClientErrorCode.UPLOAD_BATCH_COUNT_EXCEEDED,
                    String.format("批量上传最多 %d 个文件，实际 %d 个",
                            documentProperties.getMaxBatchFiles(), files.length));
        }
        long totalBytes = Arrays.stream(files).mapToLong(MultipartFile::getSize).sum();
        long maxTotalBytes = DataSize.parse(documentProperties.getMaxBatchTotalSize()).toBytes();
        if (totalBytes > maxTotalBytes) {
            throw new ClientException(ClientErrorCode.UPLOAD_BATCH_TOTAL_SIZE_EXCEEDED,
                    String.format("批量上传总大小超出限制: %s > %s",
                            DataSize.ofBytes(totalBytes).toMegabytes() + "MB",
                            documentProperties.getMaxBatchTotalSize()));
        }
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
        return PagedResult.of(result, dtoMapper::toDTO);
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
        return PagedResult.of(result, dtoMapper::toDTO);
    }

    @Override
    public DocumentDTO getById(Long id) {
        RagDocument doc = accessGuard.verifyAccess(id);
        return dtoMapper.toDTO(doc);
    }

    @Override
    public AuthorizedDocumentFile authorizeFileRead(Long id) {
        RagDocument doc = accessGuard.verifyAccess(id);
        return new AuthorizedDocumentFile(
                doc.getId(),
                doc.getFileName(),
                doc.getFileSize() != null ? doc.getFileSize() : 0L,
                doc.getMimeType(),
                doc.getBucket(),
                doc.getStorageKey()
        );
    }

    @Override
    public boolean delete(Long id) {
        RagDocument doc = accessGuard.verifyAccess(id);
        accessGuard.assertCanMutate(doc);
        return documentLifecycleService.cascadeDelete(doc);
    }

    @Override
    public List<DocumentDeleteResult> deleteBatch(List<Long> ids) {
        List<Long> distinctIds = ids.stream().distinct().toList();
        List<DocumentDeleteResult> results = new ArrayList<>(distinctIds.size());
        for (Long id : distinctIds) {
            try {
                RagDocument doc = accessGuard.verifyAccess(id);
                accessGuard.assertCanMutate(doc);
                documentLifecycleService.cascadeDelete(doc);
                results.add(new DocumentDeleteResult(id, true, null));
            } catch (ClientException | ServiceException e) {
                results.add(new DocumentDeleteResult(id, false, e.getUserMessage()));
            } catch (RuntimeException e) {
                // 非预期异常不外泄内部细节，仅记录日志；该项失败不影响其余项
                log.warn("Batch delete failed for document {}: {}", id, e.getMessage());
                results.add(new DocumentDeleteResult(id, false, "删除失败，请稍后重试"));
            }
        }
        return results;
    }

    @Override
    public DocumentUploadResponse retry(Long id) {
        RagDocument doc = accessGuard.verifyAccess(id);
        accessGuard.assertCanMutate(doc);
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
        RagDocument doc = accessGuard.verifyAccess(id);
        String groupId = doc.getDocumentGroupId();
        if (groupId == null) {
            return List.of(dtoMapper.toDTO(doc));
        }
        return ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getDocumentGroupId, groupId)
                        .orderByDesc(RagDocument::getVersion)
        ).stream().map(dtoMapper::toDTO).toList();
    }

    @Override
    public PagedResult<ChunkDTO> listChunks(Long documentId, int page, int size) {
        // 复用文档归属校验（个人文档 owner / 团队文档成员 + R1-M1 可见性分层）
        accessGuard.verifyAccess(documentId);
        int[] normalized = normalizePaging(page, size);
        int p = normalized[0];
        int s = normalized[1];
        String docIdStr = String.valueOf(documentId);
        int offset = (p - 1) * s;
        List<VectorStoreMapper.VectorStoreRow> rows =
                vectorStoreMapper.selectChunksByDocumentIdPaged(docIdStr, offset, s);
        long total = vectorStoreMapper.countChunksByDocumentId(docIdStr);
        List<ChunkDTO> content = rows.stream().map(dtoMapper::toChunkDTO).toList();
        int totalPages = s > 0 ? (int) ((total + s - 1) / s) : 0;
        return new PagedResult<>(content, p, s, total, totalPages);
    }

    @Override
    public ChunkDTO getChunk(String chunkId) {
        VectorStoreMapper.VectorStoreRow row = vectorStoreMapper.selectChunkById(chunkId);
        if (row == null) {
            throw new ServiceException(ServiceErrorCode.NOT_FOUND, "片段不存在: " + chunkId);
        }
        Long documentId = dtoMapper.parseDocumentId(row.metadata());
        if (documentId == null) {
            // 脏数据防御：无 documentId 的 chunk 拒绝访问，不泄露存在性
            log.warn("Chunk {} has no parseable documentId in metadata, denying access", chunkId);
            throw new ServiceException(ServiceErrorCode.NOT_FOUND, "片段不存在: " + chunkId);
        }
        // 复用文档归属校验：个人文档需 owner；团队文档需成员（R1-M1 可见性分层）
        accessGuard.verifyAccess(documentId);
        return dtoMapper.toChunkDTO(row);
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
}
