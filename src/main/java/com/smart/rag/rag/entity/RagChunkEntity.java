package com.smart.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * chunk ↔ entity 多对多关联表 — 对应 rag_chunk_entity（V21）
 * <p>
 * 复合主键 (chunk_id, entity_id)，使用 chunk_id 作为 MyBatis-Plus @TableId，
 * entity_id 作为普通字段。Mapper 层通过 XML 处理复合主键语义。
 */
@TableName("rag_chunk_entity")
public class RagChunkEntity {

    /** vector_store.id (UUID) */
    @TableId(type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String chunkId;

    /** rag_entity.id */
    private Long entityId;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
