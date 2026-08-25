package com.smart.rag.rag.upload;

import org.jspecify.annotations.Nullable;

/**
 * 直传会话状态响应：会话元数据（uploadId/chunkSize/totalChunks），供前端断点续传
 * 与本地分片差集计算。分片级状态由前端本地记录（localStorage 键含 sessionId），
 * 服务端不做权威差集（SDK listParts / 镜像 ListMultipartUploads 均不可用，实测）。
 */
public record DirectUploadStatusResponse(
        String sessionId,
        String status,
        String mode,
        String fileName,
        long fileSize,
        String mimeType,
        @Nullable String uploadId,
        int chunkSize,
        int totalChunks,
        @Nullable Long documentId) {
}
