package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * rag_event 表数据访问
 */
@Mapper
public interface EventMapper extends BaseMapper<RagEvent> {

    /**
     * 按文档 ID 删除事件（级联清理用；document_id 是 event 归属的权威记录，
     * 不依赖 vector_store 反查——fastTrack 临时行被删后仍能兜底）
     */
    void deleteByDocumentId(@Param("documentId") Long documentId);

    void deleteByChunkIds(@Param("chunkIds") List<String> chunkIds);

    /**
     * 插入（chunk_id 有唯一约束，冲突时忽略）
     */
    void insertIgnore(@Param("event") RagEvent event);

    /**
     * 多值批量插入（chunk_id 冲突忽略；调用方分批 ≤500 行，自动提交单语句，§3.2.1 白名单安全）
     */
    void insertIgnoreBatch(@Param("events") List<RagEvent> events);

    /**
     * 该文档已写事件的 chunkId 集合（抽取增量过滤的 rag_event 侧完成判定源）
     */
    List<String> selectChunkIdsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 孤儿事件清扫（§6 对账阶段一）：rag_event 自带 scope 列直接限定。
     */
    int deleteOrphanEventsByScope(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);

    /**
     * 孤儿事件探测（§6 阶段〇无锁只读）：谓词同 {@link #deleteOrphanEventsByScope}，仅 EXISTS。
     */
    boolean existsOrphanEventsByScope(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);
}
