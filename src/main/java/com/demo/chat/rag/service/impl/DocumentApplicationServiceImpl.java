package com.demo.chat.rag.service.impl;

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
import com.demo.chat.team.upload.UploadStrategyFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档应用服务实现
 * <p>
 * 职责：编排文档的前端操作（上传 → 查询 → 删除 → 重试）。
 * <p>
 * 上传链路委托给 {@link UploadStrategyFactory}（策略模式），
 * 校验逻辑委托给 {@link DocumentValidator}，
 * 删除的级联清理委托给 {@link DocumentLifecycleService}。
 */
@Service
public class DocumentApplicationServiceImpl implements DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationServiceImpl.class);

    private final EtlDispatchService etlDispatchService;
    private final RagDocumentMapper ragDocumentMapper;
    private final DocumentLifecycleService documentLifecycleService;
    private final UploadStrategyFactory uploadStrategyFactory;

    public DocumentApplicationServiceImpl(EtlDispatchService etlDispatchService,
                                          RagDocumentMapper ragDocumentMapper,
                                          DocumentLifecycleService documentLifecycleService,
                                          UploadStrategyFactory uploadStrategyFactory) {
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.documentLifecycleService = documentLifecycleService;
        this.uploadStrategyFactory = uploadStrategyFactory;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return uploadStrategyFactory.route(null).upload(file, null, currentUserId);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return uploadStrategyFactory.route(null).uploadBatch(files, null, currentUserId);
    }

    @Override
    public List<DocumentDTO> listAll() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<RagDocument> docs = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .eq(RagDocument::getUserId, currentUserId)
                        .orderByDesc(RagDocument::getCreateTime));
        return docs.stream().map(this::toDTO).toList();
    }

    @Override
    public DocumentDTO getById(Long id) {
        RagDocument doc = findAndVerifyOwner(id);
        return doc != null ? toDTO(doc) : null;
    }

    @Override
    public boolean delete(Long id) {
        RagDocument doc = findAndVerifyOwner(id);
        if (doc == null) {
            return false;
        }
        return documentLifecycleService.cascadeDelete(doc);
    }

    @Override
    public DocumentUploadResponse retry(Long id) {
        RagDocument doc = findAndVerifyOwner(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + id);
        }
        if (doc.getStatus() != EtlStatus.FAILED && doc.getStatus() != EtlStatus.VECTOR_FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅 FAILED / VECTOR_FAILED 状态的文档可以重试，当前状态: " + doc.getStatus());
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

    // === 私有方法 ===

    private RagDocument findAndVerifyOwner(Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!currentUserId.equals(doc.getUserId())) {
            log.warn("Access denied: userId={} attempted to access document id={} owned by userId={}",
                    currentUserId, id, doc.getUserId());
            return null;
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
                doc.getCreateTime()
        );
    }
}
