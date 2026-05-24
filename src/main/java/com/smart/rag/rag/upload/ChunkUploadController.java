package com.smart.rag.rag.upload;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.response.GlobalResponse;
import com.smart.rag.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 分片上传 REST 控制器。
 * <p>
 * URL 设计遵循 RESTful 风格：
 * <ul>
 *   <li>POST   /api/documents/multipart          — 创建上传会话（init）</li>
 *   <li>PUT    /api/documents/multipart/{id}/chunks/{index} — 上传分片</li>
 *   <li>GET    /api/documents/multipart/{id}      — 查询上传状态</li>
 *   <li>POST   /api/documents/multipart/{id}/complete — 完成上传（手动合并）</li>
 *   <li>DELETE /api/documents/multipart/{id}      — 取消上传</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/documents/multipart")
@PreAuthorize("isAuthenticated()")
public class ChunkUploadController {

    private final ChunkUploadService chunkUploadService;

    public ChunkUploadController(ChunkUploadService chunkUploadService) {
        this.chunkUploadService = chunkUploadService;
    }

    /**
     * 创建上传会话（秒传 / 新建 / 续传）。
     * <p>
     * 前端计算文件 MD5 后调用此接口，后端判断：
     * <ol>
     *   <li>文件已存在 → 秒传，直接返回文档 ID</li>
     *   <li>会话已存在 → 续传，返回已上传分片列表</li>
     *   <li>否则 → 新建会话</li>
     * </ol>
     */
    @PostMapping
    public ResponseEntity<GlobalResponse<ChunkUploadResult>> init(@Valid @RequestBody ChunkUploadInitRequest request) {
        ChunkUploadResult result = chunkUploadService.init(request);
        if (result.uploaded()) {
            return ResponseEntity.ok(GlobalResponse.ok(result, "秒传成功"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GlobalResponse.ok(result, "上传会话已创建"));
    }

    /**
     * 上传单个分片。
     * <p>
     * 请求头 X-Chunk-MD5 携带前端计算的分片 MD5，后端独立校验。
     */
    @PostMapping("/{uploadId}/chunks/{chunkIndex}")
    public GlobalResponse<ChunkUploadResponse> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestHeader("X-Chunk-MD5") String chunkMd5,
            @RequestBody byte[] chunkData) {
        if (chunkMd5 == null || !chunkMd5.matches("^[0-9a-fA-F]{32}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分片MD5格式错误");
        }
        return GlobalResponse.ok(chunkUploadService.uploadChunk(uploadId, chunkIndex, chunkMd5, chunkData));
    }

    /**
     * 查询上传状态。
     */
    @GetMapping("/{uploadId}")
    public GlobalResponse<ChunkUploadStatusResponse> status(@PathVariable String uploadId) {
        return GlobalResponse.ok(chunkUploadService.status(uploadId));
    }

    /**
     * 手动完成上传（触发合并）。
     * <p>
     * 一般情况下自动合并（最后一个分片上传完由 Lua 脚本触发）。
     * 此接口用于自动合并失败后的手动重试。
     */
    @PostMapping("/{uploadId}/complete")
    public GlobalResponse<ChunkUploadCompleteResult> complete(
            @PathVariable String uploadId,
            @Valid @RequestBody ChunkUploadCompleteRequest request) {
        Long docId = chunkUploadService.complete(uploadId, request.fileMd5());
        return GlobalResponse.ok(new ChunkUploadCompleteResult(docId), "文件合并完成");
    }

    /**
     * 取消上传，清理已上传分片。
     */
    @PostMapping("/{uploadId}/delete")
    public GlobalResponse<Void> abort(@PathVariable String uploadId) {
        chunkUploadService.abort(uploadId);
        return GlobalResponse.ok("上传已取消");
    }
}
