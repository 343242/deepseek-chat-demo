package com.demo.chat.rag.service.impl;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.EtlCandidate;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.DocumentApplicationService;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.security.util.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 文档应用服务实现
 * <p>
 * 职责：编排上传 → 存储 → 元数据 → ETL 调度 → 查询 → 删除全流程。
 * 校验逻辑委托给 {@link DocumentValidator}，
 * 删除的级联清理委托给 {@link DocumentLifecycleService}。
 */
@Service
public class DocumentApplicationServiceImpl implements DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationServiceImpl.class);

    private final FileStorageService fileStorageService;
    private final EtlDispatchService etlDispatchService;
    private final RagDocumentMapper ragDocumentMapper;
    private final MinioProperties minioProperties;
    private final DocumentValidator documentValidator;
    private final DocumentLifecycleService documentLifecycleService;

    public DocumentApplicationServiceImpl(FileStorageService fileStorageService,
                                          EtlDispatchService etlDispatchService,
                                          RagDocumentMapper ragDocumentMapper,
                                          MinioProperties minioProperties,
                                          DocumentValidator documentValidator,
                                          DocumentLifecycleService documentLifecycleService) {
        this.fileStorageService = fileStorageService;
        this.etlDispatchService = etlDispatchService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.minioProperties = minioProperties;
        this.documentValidator = documentValidator;
        this.documentLifecycleService = documentLifecycleService;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file) {
        documentValidator.validate(file);

        Long currentUserId = SecurityUtils.getCurrentUserId();
        String mimeType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        String bucket = minioProperties.getBucket();

        fileStorageService.ensureBucketExists(bucket);
        String storageKey = UUID.randomUUID().toString();
        fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

        RagDocument ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, currentUserId);
        log.info("Document uploaded: id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), currentUserId);

        etlDispatchService.dispatchAsync(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), currentUserId, ragDoc.getTeamId());

        return new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING);
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.UPLOAD_LIST_EMPTY);
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();
        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);

        for (MultipartFile file : files) {
            documentValidator.validate(file);
        }

        List<EtlCandidate> candidates = new ArrayList<>();
        List<DocumentUploadResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            String mimeType = file.getContentType();
            String originalFilename = file.getOriginalFilename();
            String storageKey = UUID.randomUUID().toString();

            fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

            RagDocument ragDoc = persistDocument(originalFilename, file.getSize(), mimeType, storageKey, bucket, currentUserId);
            log.info("Document uploaded (batch): id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), currentUserId);

            candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), currentUserId, ragDoc.getTeamId()));
            responses.add(new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING));
        }

        etlDispatchService.dispatch(candidates);

        return responses;
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

        // 清理旧的向量数据（如果有的话）
        try {
            etlDispatchService.deleteVectors(id);
        } catch (Exception e) {
            log.warn("Failed to clean old vectors for retry doc={}: {}", id, e.getMessage());
        }

        // 重置状态并异步重新执行 ETL
        etlDispatchService.dispatchAsync(id, doc.getBucket(), doc.getStorageKey(),
                doc.getFileName(), doc.getMimeType(), doc.getFileSize(), doc.getUserId(), doc.getTeamId());

        return new DocumentUploadResponse(id, doc.getFileName(), EtlStatus.PROCESSING);
    }

    // === 私有方法 ===

    private RagDocument persistDocument(String fileName, long fileSize, String mimeType,
                                        String storageKey, String bucket, Long userId) {
        RagDocument ragDoc = new RagDocument();
        ragDoc.setFileName(fileName);
        ragDoc.setFileSize(fileSize);
        ragDoc.setMimeType(mimeType);
        ragDoc.setStorageKey(storageKey);
        ragDoc.setBucket(bucket);
        ragDoc.setUserId(userId);
        ragDoc.setStatus(EtlStatus.UPLOADED);
        ragDoc.setCreateTime(LocalDateTime.now());
        ragDoc.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.insert(ragDoc);
        return ragDoc;
    }

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
