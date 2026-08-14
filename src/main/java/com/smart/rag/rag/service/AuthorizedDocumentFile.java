package com.smart.rag.rag.service;

/**
 * 授权后的文件读取描述符（设计 §5，模块内部契约）。
 * <p>
 * 由 {@code DocumentApplicationService.authorizeFileRead(id)} 在统一权限判断通过后返回。
 * {@code bucket} 与 {@code objectKey} 只在本类型中流转，不进入 Controller、不进日志、
 * 不进 JSON DTO；权限判断必须先于任何 MinIO stat 或流打开操作。
 */
public record AuthorizedDocumentFile(
        Long documentId,
        String fileName,
        long declaredFileSize,
        String canonicalMimeType,
        String bucket,
        String objectKey
) {}
