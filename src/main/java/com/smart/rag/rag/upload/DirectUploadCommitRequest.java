package com.smart.rag.rag.upload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 直传 commit 请求。
 * <p>
 * multipart 模式必填 parts（前端从各 UploadPart 响应头留存），single 模式忽略/省略。
 * ETag 由 S3 Complete 侧逐片校验（伪造即 InvalidPart），完整性最终由整对象 SHA-256 兜底。
 */
public record DirectUploadCommitRequest(@Nullable List<PartDeclaration> parts) {

    public record PartDeclaration(
            @NotNull @Min(1) int partNumber,
            @NotBlank String etag,
            @NotNull @Min(0) long size) {
    }
}
