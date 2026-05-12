package com.demo.chat.rag.upload;

import jakarta.validation.constraints.*;

/**
 * 分片上传初始化请求。
 */
public record ChunkUploadInitRequest(

    @NotBlank(message = "文件MD5不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{32}$", message = "文件MD5格式错误")
    String fileMd5,

    @NotBlank(message = "文件名不能为空")
    @Size(max = 500, message = "文件名最长500字符")
    String fileName,

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于0")
    Long fileSize,

    @NotBlank(message = "MIME类型不能为空")
    String mimeType,

    @Min(value = 1048576, message = "分片大小至少1MB")
    @Max(value = 52428800, message = "分片大小最大50MB")
    Integer chunkSize
) {}
