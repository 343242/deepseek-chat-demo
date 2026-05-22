package com.smart.rag.rag.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 分片上传合并请求。
 */
public record ChunkUploadCompleteRequest(

    @NotBlank(message = "文件MD5不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{32}$", message = "文件MD5格式错误")
    String fileMd5
) {}
