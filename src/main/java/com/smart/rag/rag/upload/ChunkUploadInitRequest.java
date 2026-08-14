package com.smart.rag.rag.upload;

import jakarta.validation.constraints.*;
import org.jspecify.annotations.Nullable;

/**
 * 分片上传初始化请求。
 *
 * @param fileChecksum 文件校验和（SHA-256，64 位十六进制）
 * @param fileName  文件名
 * @param fileSize  文件大小（字节）
 * @param mimeType  MIME 类型
 * @param chunkSize 建议分片大小（字节），null 使用默认值
 * @param teamId    团队 ID（null = 个人文档，由 Controller 层传入）
 */
public record ChunkUploadInitRequest(
    @NotBlank(message = "文件校验和不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "文件校验和格式错误")
    String fileChecksum,

    @NotBlank(message = "文件名不能为空")
    @Size(max = 500, message = "文件名最长500字符")
    String fileName,

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于0")
    Long fileSize,

    @NotBlank(message = "MIME类型不能为空")
    String mimeType,

    @Min(value = MIN_CHUNK_SIZE, message = "分片大小至少1MB")
    @Max(value = MAX_CHUNK_SIZE, message = "分片大小最大50MB")
    Integer chunkSize,

    /** 团队 ID，由 Controller 层根据路由填入，前端无需传递 */
    @Nullable
    Long teamId,

    /** 替换目标文档 ID，用于文档增量更新（null = 新文档） */
    @Nullable
    Long replaceDocumentId
) {

    /** 分片大小下限：1 MB（注解值要求编译期常量，故内联定义于此） */
    public static final long MIN_CHUNK_SIZE = 1L << 20;

    /** 分片大小上限：50 MB（与服务端单分片接收上限保持一致） */
    public static final long MAX_CHUNK_SIZE = 50L << 20;
}
