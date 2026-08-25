package com.smart.rag.rag.upload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * 直传 init 请求：声明文件元数据（字节校验后移至 commit）。
 *
 * @param fileChecksum 前端流式 SHA-256（64 hex；防反向索引 Redis key 注入畸形串，
 *                    与 ChunkUploadCompleteRequest 同款校验）
 */
public record DirectUploadInitRequest(
        @NotBlank @Size(max = 500) String fileName,
        @NotNull @Min(1) long fileSize,
        @NotBlank String mimeType,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "文件校验和格式错误") String fileChecksum,
        @Nullable Long teamId,
        @Nullable Long replaceDocumentId) {
}
