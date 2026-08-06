package com.smart.rag.infrastructure.messaging.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * outbox Mapper — relay claim/退避/dead 状态机（XML 实现，见 OutboxMapper.xml）。
 * <p>
 * claim 并发互斥靠 PG {@code FOR UPDATE SKIP LOCKED}（原生行锁），不靠 Redisson；
 * claiming 超时回收阈值绑定配置 {@code claimingTimeoutSeconds}（单源，不 SQL 硬编码）。
 */
@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEntry> {

    /**
     * 批量 claim 待投递行（短事务内调用）：
     * {@code pending 且 next_retry_at <= now}，或 {@code claiming 且超时}（now - updated_at > claimingTimeoutSeconds）。
     * {@code FOR UPDATE SKIP LOCKED} 保证多实例并发 claim 互斥。
     */
    List<OutboxEntry> claimPending(@Param("limit") int limit,
                                   @Param("now") Instant now,
                                   @Param("claimingTimeoutSeconds") long claimingTimeoutSeconds);

    /** claim 同事务内标记 claiming + 刷新 updated_at（供超时回收）。 */
    int markClaiming(@Param("ids") List<Long> ids, @Param("now") Instant now);

    /** 投递成功批量删除（评审"性能"P4：合并短事务）。 */
    int deleteByIds(@Param("ids") List<Long> ids);

    /** 真实投递失败：释放回 pending + 递增 attempts + 退避 next_retry_at。 */
    int bumpAttempts(@Param("id") Long id,
                     @Param("attempts") int attempts,
                     @Param("nextRetryAt") Instant nextRetryAt);

    /** 熔断门控 OPEN 期间顺延 next_retry_at，不动 attempts（评审 P1-7 冻结语义）。 */
    int deferForRetry(@Param("ids") List<Long> ids, @Param("nextRetryAt") Instant nextRetryAt);

    /** 重试耗尽 → dead（真正反复投递失败的毒消息）。 */
    int markDead(@Param("id") Long id, @Param("reason") String reason);

    /** pending gauge：按 topic 计数。 */
    List<TopicCount> selectPendingCountByTopic();

    /** oldest_age gauge：最早待投递行（pending/claiming）的创建时间。 */
    Instant selectOldestCreatedAt();

    /** dead 行清理（保留 dead-retention-days，走 idx_outbox_dead_cleanup 部分索引）。 */
    int deleteDeadBefore(@Param("deadline") Instant deadline);

    /** 按 topic 的 pending 计数（gauge 用）。 */
    record TopicCount(String topic, long count) {}
}
