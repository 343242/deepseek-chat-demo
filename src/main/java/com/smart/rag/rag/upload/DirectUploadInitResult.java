package com.smart.rag.rag.upload;

import org.jspecify.annotations.Nullable;

/**
 * 直传 init 响应（三态之一）。
 * <ul>
 *   <li>{@code instant}：秒传命中，上传即完成；</li>
 *   <li>{@code single}（≤5MB）：presigned PUT URL 直传后 commit；</li>
 *   <li>{@code multipart}（>5MB）：创建 MPU，分片 URL 经 part-urls 批量签发。</li>
 * </ul>
 *
 * @param expiresAt presigned URL 过期时刻（epoch ms）；过期重签无状态成本
 * @param contentType single 模式 PUT 需携带的 Content-Type（presign 未签名该头，仅提示前端）
 */
public record DirectUploadInitResult(
        String mode,
        @Nullable Long documentId,
        @Nullable String fileName,
        @Nullable String sessionId,
        @Nullable String uploadUrl,
        @Nullable Long expiresAt,
        @Nullable String contentType,
        @Nullable String uploadId,
        @Nullable Integer chunkSize,
        @Nullable Integer totalChunks) {

    public static final String MODE_INSTANT = "instant";
    public static final String MODE_SINGLE = "single";
    public static final String MODE_MULTIPART = "multipart";

    public static DirectUploadInitResult instant(Long documentId, String fileName) {
        return new DirectUploadInitResult(MODE_INSTANT, documentId, fileName,
                null, null, null, null, null, null, null);
    }

    public static DirectUploadInitResult single(String sessionId, String uploadUrl,
                                                long expiresAt, String contentType) {
        return new DirectUploadInitResult(MODE_SINGLE, null, null,
                sessionId, uploadUrl, expiresAt, contentType, null, null, null);
    }

    public static DirectUploadInitResult multipart(String sessionId, String uploadId,
                                                   int chunkSize, int totalChunks, long expiresAt) {
        return new DirectUploadInitResult(MODE_MULTIPART, null, null,
                sessionId, null, expiresAt, null, uploadId, chunkSize, totalChunks);
    }
}
