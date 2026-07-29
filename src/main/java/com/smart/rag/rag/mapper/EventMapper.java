package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rag_event 表数据访问
 */
@Mapper
public interface EventMapper extends BaseMapper<RagEvent> {

    /**
     * 按 chunk_ids 批量删除
     */
    void deleteByChunkIds(@Param("chunkIds") List<String> chunkIds);

    /**
     * 插入（chunk_id 有唯一约束，冲突时忽略）
     */
    void insertIgnore(@Param("event") RagEvent event);
}
