package com.demo.chat.team.controller;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.common.response.GlobalResponse;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.upload.*;
import com.demo.chat.team.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 团队文档分片上传 REST 控制器。
 * <p>
 * 路径设计：与个人分片上传（{@code /api/documents/multipart}）平行，
 * 团队上传路径为 {@code /api/teams/{teamId}/documents/multipart}。
 * <p>
 * teamId 由 URL 路径提供，不依赖前端传参，保证安全性。
 */
@RestController
@RequestMapping("/api/teams/{teamId}/documents/multipart")
@PreAuthorize("isAuthenticated()")
public class TeamChunkUploadController {

    private final ChunkUploadService chunkUploadService;

    public TeamChunkUploadController(ChunkUploadService chunkUploadService) {
        this.chunkUploadService = chunkUploadService;
    }

    /**
     * 创建团队文档上传会话。
     * <p>
     * 将 URL 路径中的 teamId 注入 request，由 ChunkUploadService 路由到团队 bucket。
     */
    @PostMapping
    public ResponseEntity<GlobalResponse<ChunkUploadResult>> init(
            @PathVariable Long teamId,
            @Valid @RequestBody ChunkUploadInitRequest request) {
        // 将 teamId 注入 request
        ChunkUploadInitRequest teamRequest = new ChunkUploadInitRequest(
                request.fileMd5(), request.fileName(), request.fileSize(),
                request.mimeType(), request.chunkSize(), teamId
        );
        ChunkUploadResult result = chunkUploadService.init(teamRequest);
        if (result.uploaded()) {
            return ResponseEntity.ok(GlobalResponse.ok(result, "秒传成功"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GlobalResponse.ok(result, "上传会话已创建"));
    }

    /**
     * 上传单个分片。
     * <p>
     * 分片上传不感知 teamId——uploadId 已经绑定了 bucket（在 init 阶段确定）。
     */
    @PutMapping("/{uploadId}/chunks/{chunkIndex}")
    public GlobalResponse<ChunkUploadResponse> uploadChunk(
            @PathVariable Long teamId,
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestHeader("X-Chunk-MD5") String chunkMd5,
            @RequestBody byte[] chunkData) {
        if (chunkMd5 == null || !chunkMd5.matches("^[0-9a-fA-F]{32}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分片MD5格式错误");
        }
        // chunkData 上传仅依赖 uploadId，teamId 仅用于路径语义一致性
        return GlobalResponse.ok(chunkUploadService.uploadChunk(uploadId, chunkIndex, chunkMd5, chunkData));
    }

    /**
     * 查询上传状态。
     */
    @GetMapping("/{uploadId}")
    public GlobalResponse<ChunkUploadStatusResponse> status(
            @PathVariable Long teamId,
            @PathVariable String uploadId) {
        return GlobalResponse.ok(chunkUploadService.status(uploadId));
    }

    /**
     * 手动完成上传（触发合并）。
     */
    @PostMapping("/{uploadId}/complete")
    public GlobalResponse<ChunkUploadCompleteResult> complete(
            @PathVariable Long teamId,
            @PathVariable String uploadId,
            @Valid @RequestBody ChunkUploadCompleteRequest request) {
        Long docId = chunkUploadService.complete(uploadId, request.fileMd5());
        return GlobalResponse.ok(new ChunkUploadCompleteResult(docId), "文件合并完成");
    }

    /**
     * 取消上传，清理已上传分片。
     */
    @DeleteMapping("/{uploadId}")
    public GlobalResponse<Void> abort(
            @PathVariable Long teamId,
            @PathVariable String uploadId) {
        chunkUploadService.abort(uploadId);
        return GlobalResponse.ok("上传已取消");
    }
}
