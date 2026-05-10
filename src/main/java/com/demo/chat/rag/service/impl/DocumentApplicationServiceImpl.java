package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.config.DocumentProperties;
import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.Loader;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.DocumentApplicationService;
import com.demo.chat.rag.service.EtlPipelineService;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.security.util.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文档应用服务实现
 * <p>
 * 职责：
 * <ul>
 *   <li>文件校验（MIME 白名单、非空）</li>
 *   <li>编排上传 → 存储 → 元数据 → ETL 全流程</li>
 *   <li>文档查询、删除（含存储清理）</li>
 *   <li>资源级 owner 校验：用户只能操作自己的文档</li>
 * </ul>
 * </p>
 */
@Service
public class DocumentApplicationServiceImpl implements DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationServiceImpl.class);

    private final FileStorageService fileStorageService;
    private final EtlPipelineService etlPipelineService;
    private final RagDocumentMapper ragDocumentMapper;
    private final Loader vectorStoreLoader;
    private final MinioProperties minioProperties;
    private final DocumentProperties documentProperties;

    /** 运行时解析的 MIME 白名单 */
    private volatile Set<String> cachedAllowedMimeTypes;

    public DocumentApplicationServiceImpl(FileStorageService fileStorageService,
                                          EtlPipelineService etlPipelineService,
                                          RagDocumentMapper ragDocumentMapper,
                                          Loader vectorStoreLoader,
                                          MinioProperties minioProperties,
                                          DocumentProperties documentProperties) {
        this.fileStorageService = fileStorageService;
        this.etlPipelineService = etlPipelineService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.vectorStoreLoader = vectorStoreLoader;
        this.minioProperties = minioProperties;
        this.documentProperties = documentProperties;
    }

    @Override
    public DocumentUploadResponse upload(MultipartFile file) {
        validateFile(file);

        Long currentUserId = SecurityUtils.getCurrentUserId();
        String mimeType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        String bucket = minioProperties.getBucket();

        // 存储文件
        fileStorageService.ensureBucketExists(bucket);
        String storageKey = UUID.randomUUID().toString();
        Resource fileResource = file.getResource();
        fileStorageService.upload(bucket, storageKey, fileResource, mimeType);

        // 持久化元数据
        RagDocument ragDoc = new RagDocument();
        ragDoc.setFileName(originalFilename);
        ragDoc.setFileSize(file.getSize());
        ragDoc.setMimeType(mimeType);
        ragDoc.setStorageKey(storageKey);
        ragDoc.setBucket(bucket);
        ragDoc.setUserId(currentUserId);
        ragDoc.setStatus("UPLOADED");
        ragDoc.setCreateTime(LocalDateTime.now());
        ragDoc.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.insert(ragDoc);

        log.info("Document uploaded: id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), currentUserId);

        // 触发 ETL
        etlPipelineService.execute(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType);

        return new DocumentUploadResponse(ragDoc.getId(), originalFilename, "COMPLETED");
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

        // 清理向量库中该文档的所有 chunk
        try {
            vectorStoreLoader.deleteByDocumentId(id);
        } catch (Exception e) {
            log.warn("Failed to delete vectors for documentId={}, continuing with cleanup: {}", id, e.getMessage());
        }

        fileStorageService.delete(doc.getBucket(), doc.getStorageKey());
        ragDocumentMapper.deleteById(id);
        log.info("Document deleted: id={}, file={}, userId={}", id, doc.getFileName(), doc.getUserId());
        return true;
    }

    // === 私有方法 ===

    /**
     * 查找文档并验证当前用户是否为所有者
     *
     * @param id 文档 ID
     * @return 文档实体，不存在或无权限时返回 null
     */
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

    /**
     * 校验上传文件：非空 + MIME 白名单
     *
     * @throws IllegalArgumentException 校验失败
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String mimeType = file.getContentType();
        if (mimeType == null || !getAllowedMimeTypes().contains(mimeType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + mimeType);
        }
    }

    /**
     * 从 DocumentProperties 解析并缓存 MIME 白名单
     */
    private Set<String> getAllowedMimeTypes() {
        if (cachedAllowedMimeTypes == null) {
            synchronized (this) {
                if (cachedAllowedMimeTypes == null) {
                    cachedAllowedMimeTypes = Set.of(
                            documentProperties.getAllowedMimeTypes().split(","));
                }
            }
        }
        return cachedAllowedMimeTypes;
    }

    private DocumentDTO toDTO(RagDocument doc) {
        DocumentDTO dto = new DocumentDTO();
        dto.setId(doc.getId());
        dto.setFileName(doc.getFileName());
        dto.setFileSize(doc.getFileSize());
        dto.setMimeType(doc.getMimeType());
        dto.setChunkCount(doc.getChunkCount());
        dto.setStatus(doc.getStatus());
        dto.setErrorMessage(doc.getErrorMessage());
        dto.setCreateTime(doc.getCreateTime());
        return dto;
    }
}
