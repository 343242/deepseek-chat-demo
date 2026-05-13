package com.demo.chat.team.dto;

import java.time.OffsetDateTime;

public record TeamVO(
    Long id,
    String teamName,
    String teamDesc,
    Long creatorId,
    int memberCount,
    String myRole,
    OffsetDateTime createdAt
) {}
