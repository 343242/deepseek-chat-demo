package com.demo.chat.rag.dto;

import com.demo.chat.rag.etl.EtlStatus;

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
