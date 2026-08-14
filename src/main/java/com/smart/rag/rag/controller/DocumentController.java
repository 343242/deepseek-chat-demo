package com.smart.rag.rag.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.service.DocumentApplicationService;
import com.smart.rag.rag.sse.DocumentSseRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 文档管理 REST 控制器
 */
@RestController
@RequestMapping("/api/documents")
@PreAuthorize("isAuthenticated()")
public class DocumentController {

    private final DocumentApplicationService documentService;
    private final DocumentSseRegistry sseRegistry;

    public DocumentController(DocumentApplicationService documentService, DocumentSseRegistry sseRegistry) {
        this.documentService = documentService;
        this.sseRegistry = sseRegistry;
    }

    @PostMapping("/upload")
    public GlobalResponse<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "teamId", required = false) @Nullable Long teamId,
            @RequestParam(value = "replaceDocumentId", required = false) @Nullable Long replaceDocumentId) {
        if (replaceDocumentId != null) {
            return GlobalResponse.ok(documentService.upload(file, teamId, replaceDocumentId));
        }
        return GlobalResponse.ok(documentService.upload(file, teamId));
    }

    @PostMapping("/upload/batch")
    public GlobalResponse<List<DocumentUploadResponse>> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "teamId", required = false) @Nullable Long teamId) {
        return GlobalResponse.ok(documentService.uploadBatch(files, teamId));
    }

    /**
     * 文档状态 SSE 订阅。后端 ETL 状态流转（PARSING→CHUNKING→VECTORIZING→COMPLETED/FAILED）实时推送，
     * 前端无需轮询或手动刷新。连接按 userId 索引；多实例部署下经 Redis Pub/Sub 扇出。
     * 超时 10 分钟，超时后前端 EventSource 自动重连。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        Long userId = SecurityUtils.getCurrentUserId();
        SseEmitter emitter = new SseEmitter(600_000L);
        sseRegistry.register(userId, emitter);
        return emitter;
    }

    @GetMapping
    public GlobalResponse<PagedResult<DocumentDTO>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return GlobalResponse.ok(documentService.listAll(page, size));
    }

    @GetMapping(params = "teamId")
    public GlobalResponse<PagedResult<DocumentDTO>> listByTeam(
            @RequestParam Long teamId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return GlobalResponse.ok(documentService.listByTeam(teamId, page, size));
    }

    @GetMapping("/{id}")
    public GlobalResponse<DocumentDTO> get(@PathVariable Long id) {
        return GlobalResponse.ok(documentService.getById(id));
    }

    @GetMapping("/{id}/history")
    public GlobalResponse<List<DocumentDTO>> history(@PathVariable Long id) {
        return GlobalResponse.ok(documentService.getHistory(id));
    }

    @GetMapping("/{id}/chunks")
    public GlobalResponse<PagedResult<ChunkDTO>> chunks(
            @PathVariable Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return GlobalResponse.ok(documentService.listChunks(id, page, size));
    }

    @PostMapping("/{id}/delete")
    public GlobalResponse<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return GlobalResponse.ok("文档已删除");
    }

    @PostMapping("/{id}/retry")
    public GlobalResponse<DocumentUploadResponse> retry(@PathVariable Long id) {
        return GlobalResponse.ok(documentService.retry(id));
    }
}
