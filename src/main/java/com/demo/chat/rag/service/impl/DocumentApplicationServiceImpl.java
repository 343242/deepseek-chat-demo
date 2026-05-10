package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.config.DocumentProperties;
import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.etl.EtlCandidate;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.etl.Loader;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.DocumentApplicationService;
import com.demo.chat.rag.service.EtlDispatchService;
import com.demo.chat.rag.service.FileStorageService;
import com.demo.chat.security.util.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文档应用服务实现
 * <p>
 * 职责：
 * <ul>
 *   <li>文件校验（MIME 白名单、非空、大小限制）</li>
 *   <li>编排上传 → 存储 → 元数据 → ETL 调度全流程</li>
 *   <li>文档查询、删除（含存储清理）</li>
 *   <li>资源级 owner 校验：用户只能操作自己的文档</li>
 *   <li>批量上传通过 EtlDispatchService 自动路由策略</li>
 * </ul>
 * </p>
 */
@Service
public class DocumentApplicationServiceImpl implements DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationServiceImpl.class);

    private final FileStorageService fileStorageService;
    private final EtlDispatchService etlDispatchService;
    private final RagDocumentMapper ragDocumentMapper;
    private final Loader vectorStoreLoader;
    private final MinioProperties minioProperties;
    private final DocumentProperties documentProperties;

    /** 运行时解析的 MIME 白名单 */
    private volatile Set<String> cachedAllowedMimeTypes;

    public DocumentApplicationServiceImpl(FileStorageService fileStorageService,
                                          EtlDispatchService etlDispatchService,
                                          RagDocumentMapper ragDocumentMapper,
                                          Loader vectorStoreLoader,
                                          MinioProperties minioProperties,
                                          DocumentProperties documentProperties) {
        this.fileStorageService = fileStorageService;
        this.etlDispatchService = etlDispatchService;
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
        ragDoc.setStatus(EtlStatus.UPLOADED);
        ragDoc.setCreateTime(LocalDateTime.now());
        ragDoc.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.insert(ragDoc);

        log.info("Document uploaded: id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), currentUserId);

        // 单文档走 ETL dispatch（会被路由到 StandardStrategy）
        etlDispatchService.executeSingle(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), currentUserId);

        return new DocumentUploadResponse(ragDoc.getId(), originalFilename, "COMPLETED");
    }

    @Override
    public List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("上传文件列表不能为空");
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();
        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);

        // 1. 校验所有文件
        for (MultipartFile file : files) {
            validateFile(file);
        }

        // 2. 存储文件 + 持久化元数据
        List<EtlCandidate> candidates = new ArrayList<>();
        List<DocumentUploadResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            String mimeType = file.getContentType();
            String originalFilename = file.getOriginalFilename();
            String storageKey = UUID.randomUUID().toString();

            // 存储
            fileStorageService.upload(bucket, storageKey, file.getResource(), mimeType);

            // 持久化
            RagDocument ragDoc = new RagDocument();
            ragDoc.setFileName(originalFilename);
            ragDoc.setFileSize(file.getSize());
            ragDoc.setMimeType(mimeType);
            ragDoc.setStorageKey(storageKey);
            ragDoc.setBucket(bucket);
            ragDoc.setUserId(currentUserId);
            ragDoc.setStatus(EtlStatus.UPLOADED);
            ragDoc.setCreateTime(LocalDateTime.now());
            ragDoc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.insert(ragDoc);

            log.info("Document uploaded (batch): id={}, file={}, size={}, userId={}", ragDoc.getId(), originalFilename, file.getSize(), currentUserId);

            candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), currentUserId));
            responses.add(new DocumentUploadResponse(ragDoc.getId(), originalFilename, EtlStatus.PROCESSING));
        }

        // 3. 批量调度 ETL（自动路由策略）
        etlDispatchService.dispatch(candidates);

        // 4. 更新响应状态（对于标准通道已经是最终状态，快速通道是 BM25 可用）
        for (int i = 0; i < responses.size(); i++) {
            DocumentUploadResponse resp = responses.get(i);
            RagDocument doc = ragDocumentMapper.selectById(resp.getId());
            if (doc != null) {
                responses.set(i, new DocumentUploadResponse(resp.getId(), resp.getFileName(), doc.getStatus()));
            }
        }

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

        // 清理向量库中该文档的所有 chunk（失败不影响后续清理）
        boolean vectorDeleted = false;
        try {
            vectorStoreLoader.deleteByDocumentId(id);
            vectorDeleted = true;
        } catch (Exception e) {
            log.error("Failed to delete vectors for documentId={}, will retry on next cleanup pass: {}", id, e.getMessage());
        }

        // 清理文件存储
        try {
            fileStorageService.delete(doc.getBucket(), doc.getStorageKey());
        } catch (Exception e) {
            log.error("Failed to delete file for documentId={}, storageKey={}: {}", id, doc.getStorageKey(), e.getMessage());
            // 文件删除失败不影响数据库记录删除，MinIO 可通过 lifecycle 策略回收
        }

        // 最后删除数据库记录（逻辑删除）
        ragDocumentMapper.deleteById(id);

        if (!vectorDeleted) {
            log.warn("Document {} deleted from DB/Storage but vectors remain — manual cleanup may be required", id);
        }

        log.info("Document deleted: id={}, file={}, userId={}, vectorClean={}", id, doc.getFileName(), doc.getUserId(), vectorDeleted);
        return true;
    }

    // === 私有方法 ===

    /**
     * 查找文档并验证当前用户是否为所有者
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
     * 文件魔数 → MIME 映射（用于服务端 MIME sniffing）
     */
    private static final Map<String, String> MAGIC_NUMBER_MAP = Map.of(
            "%PDF", "application/pdf",
            "PK\u0003\u0004", "application/zip"
    );

    private static final Map<String, String> EXTENSION_MIME_MAP = Map.of(
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    /**
     * 校验上传文件：非空 + 大小限制 + MIME 白名单 + 服务端 MIME 校验
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                    String.format("文件大小超出限制: %s > %s",
                            DataSize.ofBytes(file.getSize()).toMegabytes() + "MB",
                            documentProperties.getMaxFileSize()));
        }

        String declaredMimeType = file.getContentType();
        Set<String> allowed = getAllowedMimeTypes();
        if (declaredMimeType == null || !allowed.contains(declaredMimeType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + declaredMimeType);
        }

        String detectedMimeType = detectMimeType(file);
        if (detectedMimeType != null && !allowed.contains(detectedMimeType)
                && !isZipBasedOfficeDocument(declaredMimeType, detectedMimeType)) {
            throw new IllegalArgumentException(
                    String.format("文件实际类型(%s)与声明类型(%s)不匹配", detectedMimeType, declaredMimeType));
        }
    }

    private String detectMimeType(MultipartFile file) {
        try {
            byte[] header = new byte[8];
            int read = file.getInputStream().readNBytes(header, 0, 8);
            if (read < 4) return null;

            String headerStr = new String(header, 0, Math.min(read, 4));

            if (headerStr.startsWith("%PDF")) {
                return "application/pdf";
            }

            if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
                String originalName = file.getOriginalFilename();
                if (originalName != null) {
                    String lower = originalName.toLowerCase();
                    for (Map.Entry<String, String> entry : EXTENSION_MIME_MAP.entrySet()) {
                        if (lower.endsWith(entry.getKey())) {
                            return entry.getValue();
                        }
                    }
                }
                return "application/zip";
            }

            boolean allPrintable = true;
            for (int i = 0; i < read; i++) {
                byte b = header[i];
                if (b < 0x09 || (b > 0x0D && b < 0x20 && b != 0x1B)) {
                    allPrintable = false;
                    break;
                }
            }
            if (allPrintable) {
                return "text/plain";
            }

            return null;
        } catch (IOException e) {
            log.warn("Failed to detect MIME type: {}", e.getMessage());
            return null;
        }
    }

    private boolean isZipBasedOfficeDocument(String declared, String detected) {
        return "application/zip".equals(detected) && declared.contains("openxmlformats-officedocument");
    }

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
        dto.setUserId(doc.getUserId());
        dto.setCreateTime(doc.getCreateTime());
        return dto;
    }
}
