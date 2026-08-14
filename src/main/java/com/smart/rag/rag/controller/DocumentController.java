package com.smart.rag.rag.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import com.smart.rag.rag.service.DocumentApplicationService;
import com.smart.rag.rag.service.DocumentFileResult;
import com.smart.rag.rag.service.DocumentFileService;
import com.smart.rag.rag.service.Disposition;
import com.smart.rag.rag.service.RangeCapability;
import com.smart.rag.rag.sse.DocumentSseRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档管理 REST 控制器
 */
@RestController
@RequestMapping("/api/documents")
@PreAuthorize("isAuthenticated()")
public class DocumentController {

    private final DocumentApplicationService documentService;
    private final DocumentFileService documentFileService;
    private final DocumentSseRegistry sseRegistry;

    public DocumentController(DocumentApplicationService documentService,
                              DocumentFileService documentFileService,
                              DocumentSseRegistry sseRegistry) {
        this.documentService = documentService;
        this.documentFileService = documentFileService;
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

    // ==================== 原文件预览与下载（二进制输出，不包装 GlobalResponse） ====================

    /** MD/HTML 预览响应的 CSP：sandbox 隔离 + 全默认来源禁用（设计 §4.3） */
    private static final String PREVIEW_CSP =
            "sandbox; default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";

    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) @Nullable String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) @Nullable String ifRangeHeader) {
        return toBodyResponse(documentFileService.get(
                id, DocumentFileService.FilePurpose.PREVIEW, rangeHeader, ifRangeHeader));
    }

    @RequestMapping(value = "/{id}/preview", method = RequestMethod.HEAD)
    public ResponseEntity<Void> previewHead(@PathVariable Long id) {
        return toHeadResponse(documentFileService.head(id, DocumentFileService.FilePurpose.PREVIEW));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) @Nullable String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) @Nullable String ifRangeHeader) {
        return toBodyResponse(documentFileService.get(
                id, DocumentFileService.FilePurpose.DOWNLOAD, rangeHeader, ifRangeHeader));
    }

    @RequestMapping(value = "/{id}/download", method = RequestMethod.HEAD)
    public ResponseEntity<Void> downloadHead(@PathVariable Long id) {
        return toHeadResponse(documentFileService.head(id, DocumentFileService.FilePurpose.DOWNLOAD));
    }

    private ResponseEntity<Resource> toBodyResponse(DocumentFileResult result) {
        if (result instanceof DocumentFileResult.RangeNotSatisfiable notSatisfiable) {
            return toNotSatisfiable(notSatisfiable.totalSize());
        }
        if (!(result instanceof DocumentFileResult.Body body)) {
            throw new IllegalStateException("GET 必须返回 Body 或 RangeNotSatisfiable 结果");
        }
        HttpHeaders headers = commonHeaders(body.responseContentType(), body.fileName(), body.disposition());
        headers.set(HttpHeaders.ACCEPT_RANGES,
                body.rangeCapability() == RangeCapability.BYTES ? "bytes" : "none");
        headers.setContentLength(body.contentLength());
        if (body.status() == HttpStatus.PARTIAL_CONTENT) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + body.offset()
                    + "-" + (body.offset() + body.contentLength() - 1) + "/" + body.totalSize());
        }
        applyPreviewCsp(headers, body.responseContentType());
        return ResponseEntity.status(body.status()).headers(headers).body(body.resource());
    }

    private ResponseEntity<Void> toHeadResponse(DocumentFileResult result) {
        if (!(result instanceof DocumentFileResult.Metadata meta)) {
            throw new IllegalStateException("HEAD 必须返回 Metadata 结果");
        }
        HttpHeaders headers = commonHeaders(meta.responseContentType(), meta.fileName(), meta.disposition());
        headers.set(HttpHeaders.ACCEPT_RANGES,
                meta.rangeCapability() == RangeCapability.BYTES ? "bytes" : "none");
        if (meta.contentLength() != null) {
            headers.setContentLength(meta.contentLength());
        }
        applyPreviewCsp(headers, meta.responseContentType());
        return ResponseEntity.status(meta.status()).headers(headers).build();
    }

    /** 416 直接在 Controller 构造，不经过会把状态包装为 HTTP 200 的全局异常处理器 */
    private ResponseEntity<Resource> toNotSatisfiable(long totalSize) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers).build();
    }

    private HttpHeaders commonHeaders(String contentType, @Nullable String fileName, Disposition disposition) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        ContentDisposition.Builder builder = disposition == Disposition.INLINE
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        if (fileName != null && !fileName.isBlank()) {
            builder.filename(fileName, StandardCharsets.UTF_8);
        }
        headers.setContentDisposition(builder.build());
        return headers;
    }

    /** HTML 类预览输出（MD 渲染产物与 HTML）附加 CSP 沙箱头 */
    private static void applyPreviewCsp(HttpHeaders headers, String responseContentType) {
        if (responseContentType.startsWith("text/html")) {
            headers.set("Content-Security-Policy", PREVIEW_CSP);
        }
    }
}
