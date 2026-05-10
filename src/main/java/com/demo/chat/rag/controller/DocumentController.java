package com.demo.chat.rag.controller;

import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.service.DocumentApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理 REST 控制器
 * <p>
 * 职责仅限于 HTTP 协议层：参数接收、状态码映射、响应封装。
 * 所有业务逻辑委托给 {@link DocumentApplicationService}。
 * </p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentApplicationService documentService;

    public DocumentController(DocumentApplicationService documentService) {
        this.documentService = documentService;
    }

    /** 上传文档并触发 ETL 处理 */
    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        try {
            DocumentUploadResponse response = documentService.upload(file);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Upload rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /** 获取文档列表 */
    @GetMapping
    public ResponseEntity<List<DocumentDTO>> list() {
        return ResponseEntity.ok(documentService.listAll());
    }

    /** 获取文档详情 */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDTO> get(@PathVariable Long id) {
        DocumentDTO dto = documentService.getById(id);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    /** 删除文档 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean deleted = documentService.delete(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** 查询文档处理状态 */
    @GetMapping("/{id}/status")
    public ResponseEntity<DocumentDTO> getStatus(@PathVariable Long id) {
        DocumentDTO dto = documentService.getById(id);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }
}
