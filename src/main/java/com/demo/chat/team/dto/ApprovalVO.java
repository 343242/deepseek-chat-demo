package com.demo.chat.team.dto;

import java.time.OffsetDateTime;

public record ApprovalVO(
    Long id,
    Long documentId,
    String fileName,
    Long fileSize,
    Long uploaderId,
    String uploaderName,
    String status,
    Long reviewerId,
    String reviewComment,
    OffsetDateTime createdAt,
    OffsetDateTime reviewedAt
) {}
