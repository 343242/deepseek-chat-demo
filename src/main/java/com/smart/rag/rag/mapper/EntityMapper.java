package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

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

    /** updateEmbeddingBatch 批量项（id + embedding） */
    record EmbeddingUpdate(Long id, float[] embedding) {}

    /**
     * 批量更新 embedding（foreach UPDATE，见 EntityMapper.xml）
     */
    int updateEmbeddingBatch(@Param("items") List<EmbeddingUpdate> items);

    /**
     * 查询需要 embed 的实体（description 非空且 embedding 为空）
     */
    List<RagEntity> selectEntitiesNeedingEmbedding(@Param("limit") int limit);

    // ==================== 结构分写回（§5.2 / V30 §3.2.1 read-compute-write）====================

    /**
     * 社区分配项（node → community_id）。CommunityDetectionJob 将 Leiden 输出的
     * Long2IntMap 转为此列表后批量写回，避免 fastutil 类型直传 MyBatis。
     */
    record CommunityAssignment(long entityId, int communityId) {}

    /**
     * weak_tie 写回批次项（WeakTieScoreCalculator 输出 → updateWeakTieBatch）。
     */
    record WeakTieUpdate(long entityId, double weakTieScore) {}

    /**
     * bridge 写回批次项（内存 bridge 计算（Leiden 分区 + 邻接）输出 → updateBridgeBatch）。
     */
    record BridgeUpdate(long entityId, double bridgeScore) {}

    /**
     * 批量写回 Leiden 社区分配（单条 UPDATE ... FROM VALUES，按 userId 隔离）。
     * V30：Java 侧按 entityId 升序传入，须在 ScopeLockTemplate 持锁事务内执行。
     *
     * @param userId      用户作用域
     * @param teamId      团队作用域（可为 null）
     * @param communities Leiden 输出的 node → community_id 列表（entityId 升序）
     */
    void batchUpdateCommunities(@Param("userId") Long userId,
                                @Param("teamId") Long teamId,
                                @Param("communities") List<CommunityAssignment> communities);

    /**
     * weak_tie 批量写回（V30 §3.2.1：锁外内存计算的有序写回；仅含有邻居对的实体进批次）。
     * 语义对齐原 updateWeakTieScores CTE（hub degree&gt;=100 / 孤立实体不动）。
     */
    int updateWeakTieBatch(@Param("items") List<WeakTieUpdate> items);

    /**
     * bridge_score 批量写回（V30 §3.2.1：覆盖全 scope 实体含孤立实体的 reset-0 语义，
     * 取代原 updateBridgeScores 全 scope 聚合 SQL——分解为锁外内存计算 + 本有序写回）。
     */
    int updateBridgeBatch(@Param("items") List<BridgeUpdate> items);

    /**
     * 全量清除该作用域下所有实体的 community_stale（Leiden 覆盖全部节点，§5.2⑤）。
     * 非增量部分清除——degree=0 的新实体也应标记为非 stale。
     */
    void clearStaleFlag(@Param("userId") Long userId, @Param("teamId") Long teamId);

    // ==================== 对账支持（V30 §6）====================

    /**
     * 作用域行（user_id, team_id），teamId 可为 null（个人文档）。
     */
    record ScopeRow(Long userId, Long teamId) {}

    /**
     * 作用域枚举：rag_entity UNION rag_entity_cooccurrence——覆盖"实体已尽失但边残留"的
     * 异常漂移 scope（§6 第四轮修正）。
     */
    List<ScopeRow> selectDistinctScopes();

    /**
     * 实体元数据行（图快照用：id + degree——degree 供 WeakTieScoreCalculator 的 hub 预算判定）。
     */
    record EntityMeta(long id, int degree) {}

    /**
     * 读取 scope 全部实体清单（含图外孤立实体——bridge reset-0 语义需覆盖，V30 §6 阶段二）。
     */
    List<EntityMeta> selectScopeEntityMetas(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);

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
