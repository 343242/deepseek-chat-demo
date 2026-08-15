package com.smart.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.OffsetDateTime;

/**
 * 实体规范化主表 — 对应 rag_entity（V21）
 */
@TableName("rag_entity")
public class RagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规范化名称（NFC + lowercase + trim） */
    private String nameNorm;

    /** 原始展示名（取首次出现形态） */
    private String nameDisplay;

    /** 跨 chunk 拼接的实体描述 */
    private String description;

    /** description 的向量 vector(1536) */
    private float[] embedding;

    /** 所属用户 */
    private Long userId;

    /** 所属团队（null = 个人文档） */
    private Long teamId;

    /** 出现在多少个 chunk 中（派生列，由代码维护） */
    private Integer degree;

    /** P0: 弱联系分（离线计算） */
    private Double weakTieScore;

    /** P1: 桥接分（离线计算） */
    private Double bridgeScore;

    /** Leiden 社区 ID */
    private Integer communityId;

    /** 社区信息是否过期 */
    private Boolean communityStale;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNameNorm() { return nameNorm; }
    public void setNameNorm(String nameNorm) { this.nameNorm = nameNorm; }

    public String getNameDisplay() { return nameDisplay; }
    public void setNameDisplay(String nameDisplay) { this.nameDisplay = nameDisplay; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }

    public Integer getDegree() { return degree; }
    public void setDegree(Integer degree) { this.degree = degree; }

    public Double getWeakTieScore() { return weakTieScore; }
    public void setWeakTieScore(Double weakTieScore) { this.weakTieScore = weakTieScore; }

    public Double getBridgeScore() { return bridgeScore; }
    public void setBridgeScore(Double bridgeScore) { this.bridgeScore = bridgeScore; }

    public Integer getCommunityId() { return communityId; }
    public void setCommunityId(Integer communityId) { this.communityId = communityId; }

    public Boolean getCommunityStale() { return communityStale; }
    public void setCommunityStale(Boolean communityStale) { this.communityStale = communityStale; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
