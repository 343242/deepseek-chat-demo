package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagChunkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rag_chunk_entity 表数据访问
 */
@Mapper
public interface ChunkEntityMapper extends BaseMapper<RagChunkEntity> {

    /**
     * 通过 documentId（经 rag_event 关联）查出受影响的 entity_id 列表
     */
    List<Long> selectEntityIdsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按文档 ID 批量删除 chunk-entity 关联（经 rag_event 关联，覆盖 vector_store 已删的孤儿 chunk）
     */
    void deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按 chunk_ids 批量删除
     */
    void deleteByChunkIds(@Param("chunkIds") List<String> chunkIds);

    /**
     * 批量插入（ON CONFLICT DO NOTHING 处理复合 PK 冲突）
     */
    void insertBatch(@Param("list") List<RagChunkEntity> chunkEntities);
}
