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
     * 文件魔数 → MIME 映射（用于服务端 MIME sniffing）
     * 防止客户端伪造 Content-Type
     */
    private static final Map<String, String> MAGIC_NUMBER_MAP = Map.of(
            "%PDF", "application/pdf",
            "PK\u0003\u0004", "application/zip"  // docx / pptx 都是 ZIP 格式
    );

    /**
     * ZIP 内部 MIME 映射（根据文件扩展名进一步判断）
     */
    private static final Map<String, String> EXTENSION_MIME_MAP = Map.of(
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    /**
     * 校验上传文件：非空 + 大小限制 + MIME 白名单 + 服务端 MIME 校验
     *
     * @throws IllegalArgumentException 校验失败
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        // 文件大小校验
        long maxBytes = DataSize.parse(documentProperties.getMaxFileSize()).toBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                    String.format("文件大小超出限制: %s > %s",
                            DataSize.ofBytes(file.getSize()).toMegabytes() + "MB",
                            documentProperties.getMaxFileSize()));
        }

        // 客户端声明的 MIME 校验
        String declaredMimeType = file.getContentType();
        Set<String> allowed = getAllowedMimeTypes();
        if (declaredMimeType == null || !allowed.contains(declaredMimeType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + declaredMimeType);
        }

        // 服务端 MIME sniffing 校验
        String detectedMimeType = detectMimeType(file);
        if (detectedMimeType != null && !allowed.contains(detectedMimeType)
                && !isZipBasedOfficeDocument(declaredMimeType, detectedMimeType)) {
            throw new IllegalArgumentException(
                    String.format("文件实际类型(%s)与声明类型(%s)不匹配", detectedMimeType, declaredMimeType));
        }
    }

    /**
     * 检测文件实际 MIME 类型（基于文件头魔数）
     */
    private String detectMimeType(MultipartFile file) {
        try {
            byte[] header = new byte[8];
            int read = file.getInputStream().readNBytes(header, 0, 8);
            if (read < 4) return null;

            String headerStr = new String(header, 0, Math.min(read, 4));

            // PDF
            if (headerStr.startsWith("%PDF")) {
                return "application/pdf";
            }

            // ZIP-based (docx, pptx)
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

            // Plain text (ASCII/UTF-8 printable)
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

    /**
     * ZIP-based Office 文档的声明类型与检测类型兼容判断
     * docx/pptx 的检测类型是 application/zip，声明类型是具体 Office MIME，应视为兼容
     */
    private boolean isZipBasedOfficeDocument(String declared, String detected) {
        return "application/zip".equals(detected) && declared.contains("openxmlformats-officedocument");
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
