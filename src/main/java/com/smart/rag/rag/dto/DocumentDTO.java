package com.smart.rag.rag.dto;

import com.smart.rag.rag.etl.EtlStatus;

import java.time.OffsetDateTime;

/**
 * 文档信息 DTO
 *
 * @param id              文档 ID
 * @param fileName        原始文件名
 * @param fileSize        文件大小 (bytes)
 * @param mimeType        MIME 类型
 * @param chunkCount      解析后分块数
 * @param status          处理状态
 * @param errorMessage    错误信息
 * @param userId          文档所有者
 * @param teamId          所属团队 ID（null=个人文档）
 * @param version         版本号
 * @param supersededBy    被哪个文档替代（null = 当前版本）
 * @param documentGroupId 文档逻辑标识（同一文档不同版本共享）
 * @param createTime      创建时间
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
    Long teamId,
    Integer version,
    Long supersededBy,
    String documentGroupId,
    OffsetDateTime createTime
) {}
