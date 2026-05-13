package com.demo.chat.team.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.demo.chat.team.enums.ApprovalStatus;

import java.time.OffsetDateTime;

/**
 * 团队上传审批实体
 */
@TableName("team_upload_approval")
public class TeamUploadApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;

    private Long documentId;

    private Long uploaderId;

    @EnumValue
    private ApprovalStatus status;

    private Long reviewerId;

    private String reviewComment;

    private OffsetDateTime createdAt;

    private OffsetDateTime reviewedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }
    public ApprovalStatus getStatus() { return status; }
    public void setStatus(ApprovalStatus status) { this.status = status; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
