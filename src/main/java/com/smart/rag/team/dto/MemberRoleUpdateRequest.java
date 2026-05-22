package com.smart.rag.team.dto;

import com.smart.rag.team.enums.TeamMemberRole;
import jakarta.validation.constraints.NotNull;

public record MemberRoleUpdateRequest(
    @NotNull(message = "目标角色不能为空")
    TeamMemberRole targetRole
) {}
