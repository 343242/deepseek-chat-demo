package com.smart.rag.rag.service.impl;

/**
 * 服务端校验后的上传文件元数据（原文件预览与下载设计 §3.1）。
 * <p>
 * {@code canonicalMimeType} 是唯一可信类型来源：由内容探测 + 扩展名一致性 +
 * OOXML 包结构确认得出，所有上传路径以它落库、上传对象并驱动预览策略；
 * 客户端声明的 Content-Type 不参与任何决策。
 */
public record ValidatedDocumentFile(
        String fileName,
        long fileSize,
        String canonicalMimeType
) {}
