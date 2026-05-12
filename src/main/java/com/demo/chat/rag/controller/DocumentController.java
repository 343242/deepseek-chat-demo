package com.demo.chat.rag.controller;

import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.rag.dto.DocumentDTO;
import com.demo.chat.rag.dto.DocumentUploadResponse;
import com.demo.chat.rag.service.DocumentApplicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理 REST 控制器
 */
@RestController
@RequestMapping("/api/documents")
@PreAuthorize("isAuthenticated()")
public class DocumentController {

    private final DocumentApplicationService documentService;

    public DocumentController(DocumentApplicationService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public GlobalResponse<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return GlobalResponse.ok(documentService.upload(file));
    }

    @PostMapping("/upload/batch")
    public GlobalResponse<List<DocumentUploadResponse>> uploadBatch(@RequestParam("files") MultipartFile[] files) {
        return GlobalResponse.ok(documentService.uploadBatch(List.of(files)));
    }

    @GetMapping
    public GlobalResponse<List<DocumentDTO>> list() {
        return GlobalResponse.ok(documentService.listAll());
    }

    @GetMapping("/{id}")
    public GlobalResponse<DocumentDTO> get(@PathVariable Long id) {
        return GlobalResponse.ok(documentService.getById(id));
    }

    @DeleteMapping("/{id}")
    public GlobalResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return GlobalResponse.ok("文档已删除");
    }

    @PostMapping("/{id}/retry")
    public GlobalResponse<DocumentUploadResponse> retry(@PathVariable Long id) {
        return GlobalResponse.ok(documentService.retry(id));
    }
}
