package com.demo.chat.team.dto;

import com.demo.chat.team.enums.TeamMemberRole;
import jakarta.validation.constraints.NotNull;

public record MemberRoleUpdateRequest(
    @NotNull(message = "目标角色不能为空")
    TeamMemberRole targetRole
) {}
