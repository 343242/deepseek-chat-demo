package com.demo.chat.rag.controller;

import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.EtlPipelineService;
import com.demo.chat.rag.service.FileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/markdown",
            "text/html"
    );

    private final FileStorageService fileStorageService;
    private final EtlPipelineService etlPipelineService;
    private final RagDocumentMapper ragDocumentMapper;
    private final MinioProperties minioProperties;

    public DocumentController(FileStorageService fileStorageService,
                              EtlPipelineService etlPipelineService,
                              RagDocumentMapper ragDocumentMapper,
                              MinioProperties minioProperties) {
        this.fileStorageService = fileStorageService;
        this.etlPipelineService = etlPipelineService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.minioProperties = minioProperties;
    }

    /** 上传文档并触发 ETL 处理 */
    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String mimeType = file.getContentType();
        String originalFilename = file.getOriginalFilename();

        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            return ResponseEntity.badRequest().build();
        }

        String bucket = minioProperties.getBucket();
        fileStorageService.ensureBucketExists(bucket);

        String storageKey = UUID.randomUUID().toString();
        Resource fileResource = file.getResource();
        fileStorageService.upload(bucket, storageKey, fileResource, mimeType);

        RagDocument ragDoc = new RagDocument();
        ragDoc.setFileName(originalFilename);
        ragDoc.setFileSize(file.getSize());
        ragDoc.setMimeType(mimeType);
        ragDoc.setStorageKey(storageKey);
        ragDoc.setBucket(bucket);
        ragDoc.setStatus("UPLOADED");
        ragDoc.setCreateTime(LocalDateTime.now());
        ragDoc.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.insert(ragDoc);

        log.info("Document uploaded: id={}, file={}, size={}", ragDoc.getId(), originalFilename, file.getSize());

        etlPipelineService.execute(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType);

        DocumentUploadResponse response = new DocumentUploadResponse(ragDoc.getId(), originalFilename, "COMPLETED");
        return ResponseEntity.ok(response);
    }

    /** 获取文档列表 */
    @GetMapping
    public ResponseEntity<List<DocumentDTO>> list() {
        List<RagDocument> docs = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocument>()
                        .orderByDesc(RagDocument::getCreateTime));
        List<DocumentDTO> dtos = docs.stream().map(this::toDTO).toList();
        return ResponseEntity.ok(dtos);
    }

    /** 获取文档详情 */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDTO> get(@PathVariable Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDTO(doc));
    }

    /** 删除文档（同时清理 MinIO 文件） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        fileStorageService.delete(doc.getBucket(), doc.getStorageKey());
        ragDocumentMapper.deleteById(id);
        log.info("Document deleted: id={}, file={}", id, doc.getFileName());
        return ResponseEntity.noContent().build();
    }

    /** 查询文档处理状态 */
    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentDTO> getStatus(@PathVariable Long id) {
        RagDocument doc = ragDocumentMapper.selectById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDTO(doc));
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
