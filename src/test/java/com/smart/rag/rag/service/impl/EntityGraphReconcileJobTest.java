package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.ScopeRow;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper.PendingDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EntityGraphReconcileJob} 单元测试（V30 §6：阶段〇探测 / 锁内条件重写 / forceDerive /
 * 失败隔离 / §6.2 重链接）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityGraphReconcileJob — 每日对账自愈")
class EntityGraphReconcileJobTest {

    @Mock
    private EntityMapper entityMapper;
    @Mock
    private ChunkEntityMapper chunkEntityMapper;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;
    @Mock
    private RagDocumentMapper documentMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ScopeLockTemplate scopeLockTemplate;
    @Mock
    private LockRetryExecutor lockRetryExecutor;
    @Mock
    private CommunityDetectionJob communityDetectionJob;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RagEntityProperties properties;

    private EntityGraphReconcileJob job;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7,
                0.5, 0.3, 0.2, true, null, true, 0, 3, 0, 0,
                new RagEntityProperties.Reconcile(true, "0 0 8 * * *",
                        LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY
                                ? DayOfWeek.SUNDAY : DayOfWeek.MONDAY,   // forceDeriveDay 与今天错开
                        0));
        job = new EntityGraphReconcileJob(entityMapper, chunkEntityMapper, eventMapper,
                cooccurrenceMapper, documentMapper, transactionTemplate, scopeLockTemplate,
                lockRetryExecutor, communityDetectionJob, properties, eventPublisher, null);
        job.startLeadership();   // RedissonClient=null → 降级 leader=true

        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().doAnswer(invocation -> {
            Runnable body = invocation.getArgument(2);
            body.run();
            return null;
        }).when(scopeLockTemplate).withinScopeLock(any(), any(), any());
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            action.run();
            return null;
        }).when(lockRetryExecutor).execute(any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        job.shutdown();
    }

    private void stubProbeNegative() {
        lenient().when(chunkEntityMapper.existsOrphanLinksByScope(anyLong(), any())).thenReturn(false);
        lenient().when(eventMapper.existsOrphanEventsByScope(anyLong(), any())).thenReturn(false);
        lenient().when(cooccurrenceMapper.selectSourceFingerprint(anyLong(), any())).thenReturn("1:abc");
        lenient().when(cooccurrenceMapper.selectEdgeFingerprint(anyLong(), any())).thenReturn("1:abc");
    }

    @Nested
    @DisplayName("阶段〇 / 阶段一 / 阶段二门控")
    class StagedReconcileTests {

        @Test
        @DisplayName("全阴性 + 非 forceDerive → 零重写零 derive（验证 #16 常态路径）")
        void allNegative_noRewriteNoDerive() {
            stubProbeNegative();
            when(entityMapper.selectDistinctScopes()).thenReturn(List.of(new ScopeRow(1L, null)));
            when(documentMapper.selectDocsPendingEntityExtraction(any())).thenReturn(List.of());

            job.schedule();
            verify(entityMapper, timeout(2000)).selectDistinctScopes();

            verify(cooccurrenceMapper, never()).deleteByScope(anyLong(), any());
            verify(cooccurrenceMapper, never()).projectCooccurrence(anyLong(), any());
            verify(chunkEntityMapper, never()).deleteOrphanLinksByScope(anyLong(), any());
            verify(communityDetectionJob, never()).run(anyLong(), any());
        }

        @Test
        @DisplayName("指纹漂移 → 锁内孤儿清扫 + 重投影执行 + 指纹变化触发 derive（验证 #7）")
        void driftPositive_rewritesAndDerives() {
            when(chunkEntityMapper.existsOrphanLinksByScope(1L, null)).thenReturn(false);
            when(eventMapper.existsOrphanEventsByScope(1L, null)).thenReturn(false);
            when(cooccurrenceMapper.selectSourceFingerprint(1L, null)).thenReturn("2:xyz");
            // 顺序：阶段〇表侧探测 → 阶段一重写前 → 阶段一重写后（变化 → 触发 derive）
            when(cooccurrenceMapper.selectEdgeFingerprint(1L, null))
                    .thenReturn("1:abc", "1:abc", "2:xyz");
            when(entityMapper.selectDistinctScopes()).thenReturn(List.of(new ScopeRow(1L, null)));
            when(documentMapper.selectDocsPendingEntityExtraction(any())).thenReturn(List.of());

            job.schedule();
            verify(entityMapper, timeout(2000)).selectDistinctScopes();

            // 阶段一阳性即无条件执行孤儿清扫（探测为 EXISTS 谓词，清扫按 anti-join 幂等 no-op）
            verify(chunkEntityMapper, timeout(2000)).deleteOrphanLinksByScope(1L, null);
            verify(cooccurrenceMapper, timeout(2000)).deleteByScope(1L, null);
            verify(cooccurrenceMapper).projectCooccurrence(1L, null);
            verify(scopeLockTemplate).withinScopeLock(eq(1L), eq(null), any());
            verify(communityDetectionJob, timeout(2000)).run(1L, null);   // 指纹变化 → derive
        }

        @Test
        @DisplayName("孤儿阳性但指纹重写后不变 → 阶段一执行（幂等重写），derive 不触发（指纹门控）")
        void orphanSwept_fingerprintUnchanged_noDerive() {
            when(chunkEntityMapper.existsOrphanLinksByScope(1L, null)).thenReturn(true);
            when(eventMapper.existsOrphanEventsByScope(1L, null)).thenReturn(false);
            when(cooccurrenceMapper.selectSourceFingerprint(1L, null)).thenReturn("1:abc");
            when(cooccurrenceMapper.selectEdgeFingerprint(1L, null)).thenReturn("1:abc", "1:abc");
            when(entityMapper.selectDistinctScopes()).thenReturn(List.of(new ScopeRow(1L, null)));
            when(documentMapper.selectDocsPendingEntityExtraction(any())).thenReturn(List.of());

            job.schedule();
            verify(entityMapper, timeout(2000)).selectDistinctScopes();

            // 探测阳性 → 阶段一：孤儿清扫 + delete+project（幂等重写）
            verify(chunkEntityMapper, timeout(2000)).deleteOrphanLinksByScope(1L, null);
            verify(cooccurrenceMapper, timeout(2000)).deleteByScope(1L, null);
            verify(cooccurrenceMapper).projectCooccurrence(1L, null);
            // 指纹重写前后一致 → 无 derive
            verify(communityDetectionJob, never()).run(anyLong(), any());
        }

        @Test
        @DisplayName("forceDeriveDay 到达 → 全阴性也直接 derive（旁路指纹门控，验证 #13）")
        void forceDerive_bypassesGate() {
            stubProbeNegative();
            // forceDeriveDay = 今天 → 恒为 force 日（不依赖实际周几）
            RagEntityProperties.Reconcile monday =
                    new RagEntityProperties.Reconcile(true, "0 0 8 * * *",
                            LocalDate.now().getDayOfWeek(), 0);
            RagEntityProperties forceProps = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7,
                    0.5, 0.3, 0.2, true, null, true, 0, 3, 0, 0, monday);
            EntityGraphReconcileJob mondayJob = new EntityGraphReconcileJob(entityMapper, chunkEntityMapper,
                    eventMapper, cooccurrenceMapper, documentMapper, transactionTemplate, scopeLockTemplate,
                    lockRetryExecutor, communityDetectionJob, forceProps, eventPublisher, null);
            mondayJob.startLeadership();
            try {
                when(entityMapper.selectDistinctScopes()).thenReturn(List.of(new ScopeRow(1L, null)));
                when(documentMapper.selectDocsPendingEntityExtraction(any())).thenReturn(List.of());

                mondayJob.schedule();
                verify(communityDetectionJob, timeout(2000)).run(1L, null);

                verify(cooccurrenceMapper, never()).deleteByScope(anyLong(), any());   // 旁路的是 derive 门控，不触发重写
                verify(cooccurrenceMapper, never()).projectCooccurrence(anyLong(), any());
            } finally {
                mondayJob.shutdown();
            }
        }

        @Test
        @DisplayName("单 scope 失败不影响其余（失败隔离）")
        void scopeFailure_isolated() {
            stubProbeNegative();
            when(entityMapper.selectDistinctScopes()).thenReturn(List.of(
                    new ScopeRow(1L, null), new ScopeRow(2L, null)));
            when(documentMapper.selectDocsPendingEntityExtraction(any())).thenReturn(List.of());
            when(chunkEntityMapper.existsOrphanLinksByScope(1L, null)).thenThrow(new RuntimeException("probe blew up"));

            job.schedule();
            verify(entityMapper, timeout(2000)).selectDistinctScopes();

            // scope 2 仍被处理（其探测语句被调用）
            verify(chunkEntityMapper, timeout(2000)).existsOrphanLinksByScope(2L, null);
        }
    }

    @Nested
    @DisplayName("§6.2 重链接检测（全局、文档驱动）")
    class RelinkTests {

        @Test
        @DisplayName("实体表为空（TRUNCATE 后首轮）→ scope 主循环零迭代，重链接仍枚举到待重建文档（验证 #23）")
        void emptyEntityTables_relinkStillRuns() {
            when(entityMapper.selectDistinctScopes()).thenReturn(List.of());   // 实体表空
            when(documentMapper.selectDocsPendingEntityExtraction(null))
                    .thenReturn(List.of(new PendingDoc(7L, 100L, null)));

            job.schedule();
            verify(entityMapper, timeout(2000)).selectDistinctScopes();

            verify(eventPublisher, timeout(2000))
                    .publishEvent(new EtlVectorizedEvent(7L, 100L, null));
            verify(communityDetectionJob, never()).run(anyLong(), any());
        }

        @Test
        @DisplayName("逐文档发布隔离：单文档发布失败不影响其余")
        void relinkPublishFailure_isolated() {
            when(entityMapper.selectDistinctScopes()).thenReturn(List.of());
            when(documentMapper.selectDocsPendingEntityExtraction(null)).thenReturn(List.of(
                    new PendingDoc(7L, 100L, null), new PendingDoc(8L, 100L, null)));
            doThrow(new RuntimeException("publish failed"))
                    .doNothing()
                    .when(eventPublisher).publishEvent(any(EtlVectorizedEvent.class));

            job.schedule();
            verify(entityMapper, timeout(2000)).selectDistinctScopes();

            verify(eventPublisher, timeout(2000).times(2)).publishEvent(any(EtlVectorizedEvent.class));
        }

        @Test
        @DisplayName("reconcile.enabled=false → schedule 直接返回（零探测）")
        void disabled_scheduleSkips() {
            RagEntityProperties.Reconcile off =
                    new RagEntityProperties.Reconcile(false, "0 0 8 * * *", DayOfWeek.MONDAY, 0);
            RagEntityProperties disabledProps = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7,
                    0.5, 0.3, 0.2, true, null, true, 0, 3, 0, 0, off);
            EntityGraphReconcileJob offJob = new EntityGraphReconcileJob(entityMapper, chunkEntityMapper,
                    eventMapper, cooccurrenceMapper, documentMapper, transactionTemplate, scopeLockTemplate,
                    lockRetryExecutor, communityDetectionJob, disabledProps, eventPublisher, null);
            offJob.startLeadership();
            try {
                offJob.schedule();
                verify(entityMapper, after(300).never()).selectDistinctScopes();
            } finally {
                offJob.shutdown();
            }
        }
    }
}
