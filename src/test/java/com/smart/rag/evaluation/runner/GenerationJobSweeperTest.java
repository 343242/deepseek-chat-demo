package com.smart.rag.evaluation.runner;

import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import com.smart.rag.evaluation.testset.GenerationJobRecord;
import com.smart.rag.evaluation.testset.GenerationJobRepository;
import com.smart.rag.evaluation.testset.GenerationProgressSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvaluationRunSweeper#reapStaleGenerationJobs} 测试：
 * stale running（started_at）/ stale pending（created_at）/ 新鲜任务不动 / 仓储异常不毒化调度。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("生成任务 stale 清理")
class GenerationJobSweeperTest {

    @Mock
    private EvaluationResultRepository resultRepo;

    @Mock
    private EvaluationProgressSink progressSink;

    @Mock
    private GenerationJobRepository genJobRepo;

    @Mock
    private GenerationProgressSink genProgressSink;

    private EvaluationRunSweeper sweeper;

    @BeforeEach
    void setUp() {
        var props = new EvaluationProperties();
        props.getRunner().setStaleRunMinutes(30);
        sweeper = new EvaluationRunSweeper(resultRepo, progressSink,
                genJobRepo, genProgressSink, props);
    }

    private static GenerationJobRecord job(long id, String status, OffsetDateTime since) {
        return new GenerationJobRecord(id, "ds", 1L, status, null, null, null, null,
                "running".equals(status) ? since : null, null,
                "pending".equals(status) ? since : OffsetDateTime.now());
    }

    @Test
    @DisplayName("超过阈值的 running（按 started_at）与 pending（按 created_at）被标 FAILED 并完成 sink")
    void reapsStaleRunningAndPending() {
        var stale = OffsetDateTime.now().minusHours(2);
        var fresh = OffsetDateTime.now().minusMinutes(1);
        when(genJobRepo.listByStatus("running")).thenReturn(List.of(
                job(1L, "running", stale),      // stale → reap
                job(2L, "running", fresh)));    // 新鲜 → 保留
        when(genJobRepo.listByStatus("pending")).thenReturn(List.of(
                job(3L, "pending", stale),      // stale → reap（崩溃窗口孤儿）
                job(4L, "pending", fresh)));    // 新鲜 → 保留

        sweeper.reapStaleGenerationJobs();

        // 只清理 1 与 3，新鲜任务（2、4）不动
        verify(genJobRepo).markFailed(org.mockito.ArgumentMatchers.eq(1L), anyString());
        verify(genJobRepo).markFailed(org.mockito.ArgumentMatchers.eq(3L), anyString());
        verify(genJobRepo, never()).markFailed(org.mockito.ArgumentMatchers.eq(2L), anyString());
        verify(genJobRepo, never()).markFailed(org.mockito.ArgumentMatchers.eq(4L), anyString());
        verify(genProgressSink).complete(1L);
        verify(genProgressSink).complete(3L);
    }

    @Test
    @DisplayName("无 stale 任务时不做任何标记")
    void noStaleJobsNoAction() {
        when(genJobRepo.listByStatus("running")).thenReturn(List.of());
        when(genJobRepo.listByStatus("pending")).thenReturn(List.of());

        sweeper.reapStaleGenerationJobs();

        verify(genJobRepo, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("仓储异常被吞掉，不毒化调度器")
    void repoExceptionSwallowed() {
        when(genJobRepo.listByStatus("running"))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> sweeper.reapStaleGenerationJobs())
                .doesNotThrowAnyException();
        verify(genJobRepo, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("running 记录缺 started_at（数据异常）按保守策略跳过")
    void runningWithoutStartedAtSkipped() {
        var record = new GenerationJobRecord(5L, "ds", 1L, "running", null, null,
                null, null, null, null, OffsetDateTime.now().minusHours(3));
        when(genJobRepo.listByStatus("running")).thenReturn(List.of(record));
        when(genJobRepo.listByStatus("pending")).thenReturn(List.of());

        sweeper.reapStaleGenerationJobs();

        verify(genJobRepo, never()).markFailed(anyLong(), anyString());
        assertThat(Optional.ofNullable(record.startedAt())).isEmpty();
    }
}
