package com.smart.rag.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * rag_entity_cooccurrence 共现图数据访问（V30 增量维护）。
 * <p>
 * 写/删路径走增量语句（upsertIncrement / decrementByPairs / deleteZeroEdges），
 * 全量重投影（deleteByScope + projectCooccurrence）仅供对账任务锁内重写复用。
 * <p>
 * 作用域隔离：所有方法严格按 {@code user_id + COALESCE(team_id, -1)} 限定（§3.2 / §10.1）。
 */
@Mapper
public interface EntityCooccurrenceMapper {

    /**
     * 共现边行（无向，entity_a &lt; entity_b）。
     */
    record CooccurrenceRow(long entityA, long entityB, int coCount) {}

    /**
     * pair 计数行（递增/递减的批次项；entity_a &lt; entity_b 由 Java 侧 LEAST/GREATEST 规范）。
     */
    record PairCount(long entityA, long entityB, int coCount) {}

    /**
     * 读取作用域内全部共现边，供 {@code CooccurrenceGraphLoader} 构造 WeightedGraph。
     */
    List<CooccurrenceRow> selectByScope(@Param("userId") Long userId, @Param("teamId") Long teamId);

    // ==================== 作用域级 advisory 锁（V30 §3.1）====================

    /**
     * 作用域级事务内 advisory 锁（key 表达式只此一处定义）。
     * <p>
     * 契约：必须在事务内、且在取任何行锁之前调用（R1）；经 {@code ScopeLockTemplate}
     * 统一封装调用（含自动提交下的运行时断言），不得绕过模板直接调用。
     */
    void lockScope(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);

    /**
     * 持锁事务的锁等待上限（事务级 set_config，等价 SET LOCAL lock_timeout）。
     * 必须在 {@link #lockScope} 之前执行。
     */
    void setLockTimeout(@Param("millis") long millis);

    // ==================== 增量维护（V30 写/删路径）====================

    /**
     * 边递增 upsert（§4.4）：冲突目标含 LEAST/GREATEST 表达式，与 uk_cocur_scope_pair 逐字对齐。
     * 必须在 lockScope 事务内执行；批次由 Java 侧按 (a, b) 排序分批（§3.4）。
     */
    void upsertIncrement(@Param("pairs") List<PairCount> pairs,
                         @Param("userId") Long userId,
                         @Param("teamId") @Nullable Long teamId);

    /**
     * 文档当前贡献的 pair 计数（§5 删除路径锁内真值快照：该文档所有 chunk 内实体两两组合）。
     */
    List<PairCount> selectPairCountsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 对称递减（§5）：按文档 pair 计数递减共现边 co_count。必须在 lockScope 事务内执行。
     */
    void decrementByPairs(@Param("pairs") List<PairCount> pairs,
                          @Param("userId") Long userId,
                          @Param("teamId") @Nullable Long teamId);

    /**
     * 清零边删除（§5）：递减后 co_count&lt;=0 的边立即清除。必须在 lockScope 事务内执行。
     */
    int deleteZeroEdges(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);

    // ==================== 对账（V30 §6）====================

    /**
     * 清除作用域内全部共现边。仅对账锁内重投影调用（写路径不再调用）。
     */
    void deleteByScope(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 从 rag_chunk_entity 投影共现图（§5.4，幂等）。仅对账锁内重写调用。
     */
    void projectCooccurrence(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 表侧边指纹（§6）：{@code count:md5(有序 (a,b,co_count) 序列)}，空集为 {@code "0:''"}。
     */
    String selectEdgeFingerprint(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);

    /**
     * 源侧指纹（§6 阶段〇）：projectCooccurrence 的 SELECT 形状聚合，与
     * {@link #selectEdgeFingerprint} 输出格式逐字一致。无锁只读。
     */
    String selectSourceFingerprint(@Param("userId") Long userId, @Param("teamId") @Nullable Long teamId);
}
