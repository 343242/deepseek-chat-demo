package com.smart.rag.team.dto;

import java.time.OffsetDateTime;

public record TeamMemberVO(
    Long userId,
    String username,
    String nickname,
    String role,
    Long uploadLimitMb,
    OffsetDateTime joinedAt
) {}
