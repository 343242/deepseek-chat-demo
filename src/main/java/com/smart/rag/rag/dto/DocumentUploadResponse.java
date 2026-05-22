package com.smart.rag.rag.dto;

import com.smart.rag.rag.etl.EtlStatus;

/**
 * 文档上传响应 DTO
 *
 * @param id       文档 ID
 * @param fileName 原始文件名
 * @param status   处理状态
 */
public record DocumentUploadResponse(
    Long id,
    String fileName,
    EtlStatus status
) {}
