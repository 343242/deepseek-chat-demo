package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagChunkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * rag_chunk_entity 表数据访问（V30 增量维护）。
 * <p>
 * 多行写语句（insertBatchReturning / deleteByDocumentId / deleteOrphanLinksByScope）
 * 必须在 {@code ScopeLockTemplate} 持锁事务内执行（设计 §3.2.1 行锁收编）。
 */
@Mapper
public interface ChunkEntityMapper extends BaseMapper<RagChunkEntity> {

    /** insertBatchReturning 的 RETURNING 输出行（仅 DB 实际接受的链接） */
    record NewLink(String chunkId, long entityId) {}

    /** selectByChunkIds 输出行（chunk → entity 既有链接快照） */
    record ChunkLink(String chunkId, long entityId) {}

    /**
     * 按文档 ID 查询受影响实体（V30：document_id 直查，废除 rag_event 桥接）
     */
    List<Long> selectEntityIdsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按文档 ID 批量删除 chunk-entity 关联（V30：document_id 直查）
     */
    void deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 锁内读受影响 chunk 的既有链接（§4.1 步骤 4；IN 列表由 Java 侧按 500 分批）
     */
    List<ChunkLink> selectByChunkIds(@Param("chunkIds") List<String> chunkIds);

    /**
     * 链接插入：ON CONFLICT DO NOTHING + RETURNING（§4.2）。
     * 真正落库的行才出现在 RETURNING 中——增量由数据库实际接受的行决定，跨调用幂等。
     *
     * @return 实际新落库的 (chunkId, entityId) 列表（重复投递时为空）
     */
    List<NewLink> insertBatchReturning(@Param("links") List<RagChunkEntity> links);

    /**
     * 孤儿链接清扫（§6 对账阶段一）：文档已不存在（含逻辑删）但链接仍存的僵尸，
     * 经 rag_entity 限定 scope。必须在 lockScope 事务内执行。
     */
    int deleteOrphanLinksByScope(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);

    /**
     * 孤儿链接探测（§6 阶段〇无锁只读）：谓词同 {@link #deleteOrphanLinksByScope}，仅 EXISTS。
     */
    boolean existsOrphanLinksByScope(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);
}
