package com.smart.rag.team.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.smart.rag.team.enums.TeamMemberRole;

import java.time.OffsetDateTime;

/**
 * 团队成员实体
 * <p>
 * 注意：不使用 @TableLogic，使用 status 字段手动管理（同一用户可重新加入）。
 */
@TableName("team_member")
public class TeamMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;

    private Long userId;

    @EnumValue
    private TeamMemberRole role;

    private Long uploadLimitMb;

    /** 1=正常 0=已移除。不使用 @TableLogic，Mapper 查询需显式 WHERE status = 1 */
    private Integer status;

    private OffsetDateTime joinedAt;

    private OffsetDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public TeamMemberRole getRole() { return role; }
    public void setRole(TeamMemberRole role) { this.role = role; }
    public Long getUploadLimitMb() { return uploadLimitMb; }
    public void setUploadLimitMb(Long uploadLimitMb) { this.uploadLimitMb = uploadLimitMb; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime joinedAt) { this.joinedAt = joinedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
