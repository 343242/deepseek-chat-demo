package com.demo.chat.rag.controller;

import com.demo.chat.rag.config.DocumentProperties;
import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.EtlPipelineService;
import com.demo.chat.rag.service.FileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final FileStorageService fileStorageService;
    private final EtlPipelineService etlPipelineService;
    private final RagDocumentMapper ragDocumentMapper;
    private final MinioProperties minioProperties;
    private final DocumentProperties documentProperties;

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/markdown",
            "text/html"
    );

    /**
     * 上传文档并触发 ETL 处理
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        // 校验文件非空
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String mimeType = file.getContentType();
        String originalFilename = file.getOriginalFilename();

        // 校验 MIME 类型
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            return ResponseEntity.badRequest().build();
        }

        // 确保 bucket 存在
        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);

        // 生成存储 key（UUID 避免冲突）
        String storageKey = UUID.randomUUID().toString();

        // 上传到 MinIO
        Resource fileResource = file.getResource();
        fileStorageService.upload(bucket, storageKey, fileResource, mimeType);

        // 创建数据库记录
        RagDocument ragDoc = RagDocument.builder()
                .fileName(originalFilename)
                .fileSize(file.getSize())
                .mimeType(mimeType)
                .storageKey(storageKey)
                .bucket(bucket)
                .status("UPLOADED")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        ragDocumentMapper.insert(ragDoc);

        log.info("Document uploaded: id={}, file={}, size={}", ragDoc.getId(), originalFilename, file.getSize());

        // 触发 ETL
        int chunkCount = etlPipelineService.execute(
                ragDoc.getId(), bucket, storageKey, originalFilename, mimeType);

        DocumentUploadResponse response = DocumentUploadResponse.builder()
                .id(ragDoc.getId())
                .fileName(originalFilename)
                .status("COMPLETED")
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 获取文档列表
     */
    @GetMapping
    public ResponseEntity<List<DocumentDTO>> list() {
        List<RagDocument> docs = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .orderByDesc(RagDocument::getCreateTime)
        );
        List<DocumentDTO> dtos = docs.stream().map(this::toDTO).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDTO> get(@PathVariable Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDTO(doc));
    }

    /**
     * 删除文档（同时清理 MinIO 文件）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }

        // 删除 MinIO 文件
        fileStorageService.delete(doc.getBucket(), doc.getStorageKey());

        // 逻辑删除数据库记录
        ragDocumentMapper.deleteById(id);

        log.info("Document deleted: id={}, file={}", id, doc.getFileName());
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询文档处理状态
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentDTO> getStatus(@PathVariable Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDTO(doc));
    }

    private DocumentDTO toDTO(RagDocument doc) {
        return DocumentDTO.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .fileSize(doc.getFileSize())
                .mimeType(doc.getMimeType())
                .chunkCount(doc.getChunkCount())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .createTime(doc.getCreateTime())
                .build();
    }
}
