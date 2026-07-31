package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import com.smart.rag.rag.retrieval.entity.ExpandedChunk;
import com.smart.rag.rag.retrieval.entity.ScoredEntity;
import com.smart.rag.rag.retrieval.entity.VotedChunk;

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

    // ==================== 结构分写回（§5.2，CommunityDetectionJob 调用）====================

    /**
     * 社区分配项（node → community_id）。CommunityDetectionJob 将 Leiden 输出的
     * Long2IntMap 转为此列表后批量写回，避免 fastutil 类型直传 MyBatis。
     */
    record CommunityAssignment(long entityId, int communityId) {}

    /**
     * 批量写回 Leiden 社区分配（单条 UPDATE ... FROM VALUES，按 userId 隔离）。
     *
     * @param userId      用户作用域
     * @param teamId      团队作用域（可为 null）
     * @param communities Leiden 输出的 node → community_id 列表
     */
    void batchUpdateCommunities(@Param("userId") Long userId,
                                @Param("teamId") Long teamId,
                                @Param("communities") List<CommunityAssignment> communities);

    /**
     * 计算 bridge_score：邻居中属于不同社区的数量（排除自身社区，§5.2 Step 2 纯 SQL）。
     * LEFT JOIN + FILTER 保证所有在作用域内的实体都被覆盖（非桥实体 reset 为 0），重跑幂等。
     */
    void updateBridgeScores(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 全量清除该作用域下所有实体的 community_stale（Leiden 覆盖全部节点，§5.2⑤）。
     * 非增量部分清除——degree=0 的新实体也应标记为非 stale。
     */
    void clearStaleFlag(@Param("userId") Long userId, @Param("teamId") Long teamId);

    // ==================== Path C 在线检索查询（§6.2/§6.3/§6.4，仅读取）====================

    /**
     * 融合排序 frontier（§6.2）：seed embeddings 向量匹配 + window-max 归一化 + composite_score 剪枝。
     * 归一化在 SQL 内完成，Java 层直接使用 queryRelNorm/bridgeNorm/weakTieNorm/compositeScore。
     */
    List<ScoredEntity> findFrontierEntities(@Param("seedEmbeddings") List<float[]> seedEmbeddings,
                                            @Param("matchThreshold") double matchThreshold,
                                            @Param("userId") long userId,
                                            @Param("teamId") Long teamId,
                                            @Param("frontierBudget") int frontierBudget,
                                            @Param("alpha") double alpha,
                                            @Param("beta") double beta,
                                            @Param("gamma") double gamma);

    /**
     * 投票回链 chunks（§6.3 UnWeaver）：frontier → rag_chunk_entity → vector_store，max 聚合 + array_agg。
     * 传入 frontier ScoredEntity 列表（id + composite_score + name_display），SQL 用 VALUES 展开。
     */
    List<VotedChunk> voteBacklinkChunks(@Param("frontier") List<ScoredEntity> frontier,
                                        @Param("chunkTopK") int chunkTopK,
                                        @Param("userIdStr") String userIdStr);

    /**
     * SAG 结构扩展（§6.4）：frontier → rag_event → 新 entities → 新 events → 新 chunks，H=1 单跳，纯结构 JOIN。
     * 传入 frontier ScoredEntity 列表（id + composite_score），δ 衰减后作为结构传递分。
     */
    List<ExpandedChunk> expandChunks(@Param("frontier") List<ScoredEntity> frontier,
                                     @Param("expansionDecay") double expansionDecay,
                                     @Param("expandChunkTopK") int expandChunkTopK,
                                     @Param("userId") long userId,
                                     @Param("teamId") Long teamId,
                                     @Param("userIdStr") String userIdStr);
}
