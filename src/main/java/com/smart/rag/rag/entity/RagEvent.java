package com.smart.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.OffsetDateTime;

/**
 * chunk 级事件摘要表 — 对应 rag_event（V21）
 */
@TableName("rag_event")
public class RagEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应 vector_store.id（UUID），1:1 */
    private String chunkId;

    /** LLM 生成的事件摘要 */
    private String summary;

    /** 事件摘要向量 vector(1536) */
    private float[] embedding;

    /** 所属用户 */
    private Long userId;

    /** 所属团队（null = 个人文档） */
    private Long teamId;

    /** 所属文档 ID */
    private Long documentId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
