package com.smart.rag.team.dto;

import java.time.OffsetDateTime;

public record TeamDetailVO(
    Long id,
    String teamName,
    String teamDesc,
    Long creatorId,
    String creatorName,
    int memberCount,
    int documentCount,
    long defaultUploadLimitMb,
    long creatorUploadLimitMb,
    String myRole,
    OffsetDateTime createdAt
) {}
