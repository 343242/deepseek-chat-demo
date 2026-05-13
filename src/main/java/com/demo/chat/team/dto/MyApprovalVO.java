package com.demo.chat.team.dto;

import java.time.OffsetDateTime;

public record MyApprovalVO(
    Long id,
    Long documentId,
    String fileName,
    String status,
    Long reviewerId,
    String reviewComment,
    OffsetDateTime createdAt,
    OffsetDateTime reviewedAt
) {}
