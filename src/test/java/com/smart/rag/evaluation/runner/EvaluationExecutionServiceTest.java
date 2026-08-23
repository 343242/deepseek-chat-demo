package com.smart.rag.evaluation.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.result.EvaluationResultRepository;
import com.smart.rag.evaluation.runner.EvaluationRunner.EvalConfig;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link EvaluationExecutionService} 提交补偿与配置校验测试：
 * executor 拒绝时同步标记 FAILED + 完成 sink + 抛 ServiceException（不留 running 孤儿）；
 * configOverride 的类型/范围校验在此层收敛为 400。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评估执行服务（提交补偿/配置校验）")
class EvaluationExecutionServiceTest {

    @Mock
    private EvaluationRunner runner;

    @Mock
    private EvaluationResultRepository resultRepo;

    @Mock
    private DatasetRepository datasetRepo;

    @Mock
    private ExecutorService executor;

    private EvaluationProgressSink sink;
    private EvaluationProperties props;
    private EvaluationExecutionService service;
    private Semaphore semaphore;

    @BeforeEach
    void setUp() {
        sink = new EvaluationProgressSink();
        semaphore = new Semaphore(1);
        props = new EvaluationProperties();
        service = new EvaluationExecutionService(runner, resultRepo, datasetRepo, props,
                new ObjectMapper(), executor, semaphore, sink, mock(ScopedTasks.class));
    }

    private static EvaluationRun run(long id) {
        return new EvaluationRun(id, 1L, "run-" + id, "{}", null, null, null,
                null, null, null, null);
    }

    @Test
    @DisplayName("submitRun：executor 拒绝时标记 FAILED、完成 sink 并抛 ServiceException，不误释放信号量")
    void submitRejectedMarksFailedAndCompletesSink() {
        doThrow(new RejectedExecutionException("shutdown"))
                .when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.submitRun(run(7L), List.of(), service.buildEvalConfig(null)))
                .isInstanceOf(ServiceException.class);

        var summary = ArgumentCaptor.forClass(String.class);
        verify(resultRepo).updateRunStatus(eq(7L), eq(EvaluationRunStatus.FAILED), summary.capture());
        assertThat(summary.getValue()).contains("executor rejected");
        // sink 已 complete 释放：晚到订阅得到立即完成的空流，不会挂到超时
        assertThat(sink.subscribe(7L).collectList().block()).isEmpty();
        // 任务从未 acquire，信号量许可未被误释放（否则击穿 max-concurrent-runs 背压）
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("buildEvalConfig：topK 越界（0/负数/超上限）与类型错误均抛 ClientException（400）")
    void buildEvalConfigValidatesTopK() {
        assertThatThrownBy(() -> service.buildEvalConfig(Map.of("topK", 0)))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.buildEvalConfig(Map.of("topK", -3)))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.buildEvalConfig(Map.of("topK", 101)))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.buildEvalConfig(Map.of("topK", "10")))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.buildEvalConfig(Map.of("rerankEnabled", "yes")))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service.buildEvalConfig(Map.of("testUserId", "abc")))
                .isInstanceOf(ClientException.class);

        EvalConfig config = service.buildEvalConfig(Map.of("topK", 50));
        assertThat(config.getTopK()).isEqualTo(50);
        assertThat(config.getVectorTopK()).isEqualTo(50);
        assertThat(config.getBm25TopK()).isEqualTo(50);
    }

    @Test
    @DisplayName("buildEvalConfig：null/空 override 返回默认配置，不抛异常")
    void buildEvalConfigToleratesEmptyOverride() {
        EvalConfig config = service.buildEvalConfig(null);
        assertThat(config.getVectorTopK()).isEqualTo(props.getRunner().getDefaultK());
        EvalConfig empty = service.buildEvalConfig(Map.of());
        assertThat(empty.isRerankEnabled()).isTrue();
    }
}
