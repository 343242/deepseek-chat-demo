package com.smart.rag.team.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.smart.rag.team.enums.TeamStatus;

import java.time.OffsetDateTime;

/**
 * 团队实体
 */
@TableName("team")
public class Team {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String teamName;

    private String teamDesc;

    private Long creatorId;

    private Long defaultUploadLimitMb;

    private Long creatorUploadLimitMb;

    @EnumValue
    private TeamStatus status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getTeamDesc() { return teamDesc; }
    public void setTeamDesc(String teamDesc) { this.teamDesc = teamDesc; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Long getDefaultUploadLimitMb() { return defaultUploadLimitMb; }
    public void setDefaultUploadLimitMb(Long defaultUploadLimitMb) { this.defaultUploadLimitMb = defaultUploadLimitMb; }
    public Long getCreatorUploadLimitMb() { return creatorUploadLimitMb; }
    public void setCreatorUploadLimitMb(Long creatorUploadLimitMb) { this.creatorUploadLimitMb = creatorUploadLimitMb; }
    public TeamStatus getStatus() { return status; }
    public void setStatus(TeamStatus status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
