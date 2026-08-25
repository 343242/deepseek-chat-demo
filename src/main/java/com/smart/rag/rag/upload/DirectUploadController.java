package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.rag.dto.DocumentUploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * Presigned 直传 REST 控制器（个人空间）。
 * <p>
 * 控制面端点（数据面 = 浏览器 PUT MinIO，不经后端）：
 * <ul>
 *   <li>POST /api/documents/direct-uploads                      — init（秒传/single/multipart）</li>
 *   <li>POST /api/documents/direct-uploads/{id}/part-urls       — 批量签发分片 URL（单批 ≤20）</li>
 *   <li>GET  /api/documents/direct-uploads/{id}                 — 会话状态（续传元数据）</li>
 *   <li>POST /api/documents/direct-uploads/{id}/commit          — 确认：校验+落库+ETL</li>
 *   <li>POST /api/documents/direct-uploads/{id}/abort           — 取消（204）</li>
 * </ul>
 * 团队镜像：{@code TeamDirectUploadController}（/api/teams/{teamId}/documents/direct-uploads）。
 */
@RestController
@RequestMapping("/api/documents/direct-uploads")
@PreAuthorize("isAuthenticated()")
public class DirectUploadController {

    private final DirectUploadService directUploadService;

    public DirectUploadController(DirectUploadService directUploadService) {
        this.directUploadService = directUploadService;
    }

    /** init：声明文件元数据 → 秒传命中 / 签发 single URL / 创建 MPU。 */
    @PostMapping
    public ResponseEntity<GlobalResponse<DirectUploadInitResult>> init(
            @Valid @RequestBody DirectUploadInitRequest request) {
        DirectUploadInitResult result = directUploadService.init(new DirectUploadInitRequest(
                request.fileName(), request.fileSize(), request.mimeType(), request.fileChecksum(),
                null, request.replaceDocumentId()));
        if (DirectUploadInitResult.MODE_INSTANT.equals(result.mode())) {
            return ResponseEntity.ok(GlobalResponse.ok(result, "秒传成功"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GlobalResponse.ok(result, "直传会话已创建"));
    }

    /** 批量签发分片 presigned URL。 */
    @PostMapping("/{sessionId}/part-urls")
    public GlobalResponse<DirectUploadPartUrlsResult> partUrls(
            @PathVariable String sessionId,
            @Valid @RequestBody DirectUploadPartUrlsRequest request) {
        return GlobalResponse.ok(directUploadService.partUrls(sessionId, request, null));
    }

    /** 会话状态查询（断点续传元数据）。 */
    @GetMapping("/{sessionId}")
    public GlobalResponse<DirectUploadStatusResponse> status(@PathVariable String sessionId) {
        return GlobalResponse.ok(directUploadService.status(sessionId, null));
    }

    /** commit：事实校验 + 合并/复核 + copy + 落库 + ETL。 */
    @PostMapping("/{sessionId}/commit")
    public GlobalResponse<DocumentUploadResponse> commit(
            @PathVariable String sessionId,
            @Valid @RequestBody DirectUploadCommitRequest request) {
        return GlobalResponse.ok(directUploadService.commit(sessionId, request, null), "上传完成");
    }

    /** 取消：AbortMultipartUpload / 删 pending + 会话清理。 */
    @PostMapping("/{sessionId}/abort")
    public ResponseEntity<GlobalResponse<Void>> abort(@PathVariable String sessionId) {
        directUploadService.abort(sessionId, null);
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(GlobalResponse.ok(null, "已取消"));
    }
}
