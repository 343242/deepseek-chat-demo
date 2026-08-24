package com.smart.rag.rag.service.impl;

import com.smart.rag.config.NamedThreadFactory;
import com.smart.rag.infrastructure.messaging.outbox.RedissonLeadership;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.ScopeRow;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper.PendingDoc;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 每日 8:00 对账自愈（V30 §6，第三支柱：最终一致兜底）。
 * <p>
 * per-scope 三阶段：
 * <ul>
 *   <li><b>阶段〇</b>（无锁只读探测）：源侧/表侧指纹对比 + 孤儿 EXISTS——常态零锁零重写；</li>
 *   <li><b>阶段一</b>（仅探测阳性）：锁内孤儿清扫 + deleteByScope + projectCooccurrence
 *       （不变式重投影，幂等）+ 指纹前后对比；</li>
 *   <li><b>阶段二</b>（【rewrote 且指纹变化】或 forceDerive）：derive 链
 *       （{@link CommunityDetectionJob#run}——锁外计算 + 锁内写回）。</li>
 * </ul>
 * 周强制 derive（forceDeriveDay，默认周一）：旁路指纹门控，自愈结构分自身漂移（#13）。
 * §6.2 重链接检测（全局、文档驱动）：抽取标记为 NULL 的在册 COMPLETED 文档重发
 * {@link EtlVectorizedEvent}——写路径重试耗尽的最终自愈通道 + V30 TRUNCATE 后首轮自动重建。
 * <p>
 * 独立单线程 executor（对账是小时级批处理，不得占用共享 @Scheduled 调度线程）+ 具名线程 +
 * {@code @PreDestroy} shutdown；多实例防重复用 {@link RedissonLeadership}
 * （复用 outbox 先例：看门狗续约、崩溃 ~30s 接管、Redis 未配置降级每实例执行——幂等保证正确性，仅浪费）。
 */
@Component
public class EntityGraphReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(EntityGraphReconcileJob.class);

    private static final int EDGE_BATCH_SIZE = 500;

    private final EntityMapper entityMapper;
    private final ChunkEntityMapper chunkEntityMapper;
    private final EventMapper eventMapper;
    private final EntityCooccurrenceMapper cooccurrenceMapper;
    private final RagDocumentMapper documentMapper;
    private final TransactionTemplate transactionTemplate;
    private final ScopeLockTemplate scopeLockTemplate;
    private final LockRetryExecutor lockRetryExecutor;
    private final CommunityDetectionJob communityDetectionJob;
    private final RagEntityProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /** 复用 outbox 的 RedissonLeadership 模式（§3.7）；RedissonClient 缺失降级每实例执行。 */
    private final RedissonLeadership leadership;

    private final ExecutorService reconcileExecutor =
            Executors.newSingleThreadExecutor(new NamedThreadFactory("entity-graph-reconcile", true));

    @Autowired
    public EntityGraphReconcileJob(EntityMapper entityMapper,
                                   ChunkEntityMapper chunkEntityMapper,
                                   EventMapper eventMapper,
                                   EntityCooccurrenceMapper cooccurrenceMapper,
                                   RagDocumentMapper documentMapper,
                                   TransactionTemplate transactionTemplate,
                                   ScopeLockTemplate scopeLockTemplate,
                                   LockRetryExecutor lockRetryExecutor,
                                   CommunityDetectionJob communityDetectionJob,
                                   RagEntityProperties properties,
                                   ApplicationEventPublisher eventPublisher,
                                   @Autowired(required = false) @Nullable RedissonClient redisson) {
        this.entityMapper = entityMapper;
        this.chunkEntityMapper = chunkEntityMapper;
        this.eventMapper = eventMapper;
        this.cooccurrenceMapper = cooccurrenceMapper;
        this.documentMapper = documentMapper;
        this.transactionTemplate = transactionTemplate;
        this.scopeLockTemplate = scopeLockTemplate;
        this.lockRetryExecutor = lockRetryExecutor;
        this.communityDetectionJob = communityDetectionJob;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.leadership = new RedissonLeadership(redisson,
                "smart-rag:leader:entity-graph-reconcile", Duration.ofSeconds(10));
    }

    @PostConstruct
    void startLeadership() {
        leadership.start();
    }

    @PreDestroy
    void shutdown() {
        leadership.stop();
        reconcileExecutor.shutdownNow();
    }

    @Scheduled(cron = "${app.rag.entity.reconcile.cron:0 0 8 * * *}")
    public void schedule() {
        if (!properties.reconcile().enabled()) {
            return;
        }
        if (!leadership.isLeader()) {
            log.debug("Not leader, skipping entity graph reconcile");
            return;
        }
        reconcileExecutor.submit(this::reconcileAll);
    }

    private void reconcileAll() {
        // 每周强制 derive（§6）：旁路指纹门控，自愈结构分自身漂移
        boolean forceDerive = LocalDate.now().getDayOfWeek() == properties.reconcile().forceDeriveDay();
        log.info("Entity graph reconcile started (forceDerive={})", forceDerive);

        // scope 枚举：rag_entity UNION rag_entity_cooccurrence——覆盖"实体尽失但边残留"的漂移 scope
        for (ScopeRow scope : entityMapper.selectDistinctScopes()) {
            try {
                reconcileScope(scope, forceDerive);
            } catch (Exception e) {
                // 失败隔离：单 scope 失败不影响其余
                log.error("Reconcile failed for scope userId={}, teamId={}",
                        scope.userId(), scope.teamId(), e);
            }
        }

        relinkDocumentsMissingExtraction();
        log.info("Entity graph reconcile finished");
    }

    /** per-scope 三阶段（§6）。 */
    private void reconcileScope(ScopeRow scope, boolean forceDerive) {
        Long userId = scope.userId();
        Long teamId = scope.teamId();

        // 阶段〇：无锁只读探测（MVCC 说明：指纹是两条独立语句，语句间并发写可致假阳性 →
        // 进入阶段一重投影幂等无害（宁可错杀）；写路径对链接与边的同事务原子提交保证快照内部自洽）
        boolean orphanLinks = chunkEntityMapper.existsOrphanLinksByScope(userId, teamId);
        boolean orphanEvents = eventMapper.existsOrphanEventsByScope(userId, teamId);
        boolean drift = !Objects.equals(
                cooccurrenceMapper.selectSourceFingerprint(userId, teamId),
                cooccurrenceMapper.selectEdgeFingerprint(userId, teamId));

        boolean rewrote = false;
        String[] fingerprint = new String[2];
        if (orphanLinks || orphanEvents || drift) {
            log.info("Reconcile probe positive for userId={}, teamId={} (orphanLinks={}, orphanEvents={}, drift={})",
                    userId, teamId, orphanLinks, orphanEvents, drift);
            // 阶段一：锁内条件重写（孤儿清扫先于重投影——重投影会把僵尸当真值固化）
            lockRetryExecutor.execute(() ->
                    transactionTemplate.executeWithoutResult(status ->
                            scopeLockTemplate.withinScopeLock(userId, teamId, () -> {
                                chunkEntityMapper.deleteOrphanLinksByScope(userId, teamId);
                                eventMapper.deleteOrphanEventsByScope(userId, teamId);
                                fingerprint[0] = cooccurrenceMapper.selectEdgeFingerprint(userId, teamId);
                                cooccurrenceMapper.deleteByScope(userId, teamId);
                                cooccurrenceMapper.projectCooccurrence(userId, teamId);
                                fingerprint[1] = cooccurrenceMapper.selectEdgeFingerprint(userId, teamId);
                            })));
            rewrote = true;
        } else if (!forceDerive) {
            return;   // 常态路径：零锁、零重写、无长事务（#16）
        }

        // 阶段二（锁外计算 + 锁内写回）：仅当【rewrote 且指纹变化】或 forceDerive
        boolean derive = forceDerive || (rewrote && !Objects.equals(fingerprint[0], fingerprint[1]));
        if (derive) {
            communityDetectionJob.run(userId, teamId);
        }
    }

    /**
     * §6.2 重链接检测（全局、无 scope 参数——scope 枚举源是 rag_document 自身：
     * rag_document 不在 TRUNCATE 清单内、行自带 user_id/team_id 恰为发布事件所需参数）。
     * 命中文档逐个发布 EtlVectorizedEvent（监听器 @Async 异步执行，不阻塞对账线程），
     * 重抽走全量幂等路径（RETURNING 驱动增量 + graphChanged 门控）。
     */
    private void relinkDocumentsMissingExtraction() {
        int limit = properties.reconcile().relinkLimit();
        List<PendingDoc> pending = documentMapper.selectDocsPendingEntityExtraction(limit > 0 ? limit : null);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Relinking {} documents with pending entity extraction (EtlStatus={})",
                pending.size(), EtlStatus.COMPLETED);
        for (PendingDoc doc : pending) {
            try {
                eventPublisher.publishEvent(new EtlVectorizedEvent(doc.documentId(), doc.userId(), doc.teamId()));
            } catch (Exception e) {
                // 逐文档隔离：单文档发布失败不影响其余
                log.error("Failed to publish relink event for documentId={}", doc.documentId(), e);
            }
        }
    }
}
