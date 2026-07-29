package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rag_entity 表数据访问
 */
@Mapper
public interface EntityMapper extends BaseMapper<RagEntity> {

    /**
     * 批量 UPSERT：按 name_norm + user_id + COALESCE(team_id,-1) 冲突时追加 description
     */
    void upsertByNormUserTeam(@Param("list") List<RagEntity> entities);

    /**
     * 重算 degree：对指定 entity ids 设置 degree = rag_chunk_entity 行数
     */
    void recalculateDegree(@Param("entityIds") List<Long> entityIds);

    /**
     * 删除 degree=0 的孤儿实体（仅限指定 ID 列表）
     *
     * @param entityIds 受影响的实体 ID 列表
     * @return 删除行数
     */
    int deleteOrphans(@Param("entityIds") List<Long> entityIds);

    /**
     * 标记指定实体 community_stale=TRUE
     */
    void markCommunityStale(@Param("entityIds") List<Long> entityIds);

    /**
     * 批量更新 embedding
     */
    void updateEmbedding(@Param("id") Long id, @Param("embedding") float[] embedding);

    /**
     * 查询需要 embed 的实体（description 非空且 embedding 为空）
     */
    List<RagEntity> selectEntitiesNeedingEmbedding(@Param("limit") int limit);
}
