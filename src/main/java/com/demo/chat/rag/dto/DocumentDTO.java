package com.demo.chat.rag.dto;

import com.demo.chat.rag.etl.EtlStatus;

import java.time.LocalDateTime;

/**
 * 文档信息 DTO
 *
 * @param id           文档 ID
 * @param fileName     原始文件名
 * @param fileSize     文件大小 (bytes)
 * @param mimeType     MIME 类型
 * @param chunkCount   解析后分块数
 * @param status       处理状态
 * @param errorMessage 错误信息
 * @param userId       文档所有者
 * @param createTime   创建时间
 */
public record DocumentDTO(
    Long id,
    String fileName,
    Long fileSize,
    String mimeType,
    Integer chunkCount,
    EtlStatus status,
    String errorMessage,
    Long userId,
    LocalDateTime createTime
) {}
