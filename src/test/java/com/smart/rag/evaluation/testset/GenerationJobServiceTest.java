package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.EvaluationDataset;
import com.smart.rag.infrastructure.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GenerationJobService} 异步生命周期测试：executor 桩为"同步直跑/拒绝/不跑"三种形态，
 * 覆盖提交、成功、信号量超时、生成异常、executor 拒绝与 404 六条路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("测试集生成任务服务")
class GenerationJobServiceTest {

    @Mock
    private TestsetGeneratorService generator;

    @Mock
    private GenerationJobRepository jobRepo;

    @Mock
    private ExecutorService executor;

    private GenerationProgressSink sink;
    private EvaluationProperties props;
    private GenerationJobService service;
    private Semaphore semaphore;

    @BeforeEach
    void setUp() {
        sink = new GenerationProgressSink();
        semaphore = new Semaphore(1);
        props = new EvaluationProperties();
        props.getRunner().setAcquireTimeoutSeconds(60);
        service = new GenerationJobService(generator, jobRepo, sink, props,
                new ObjectMapper(), executor, semaphore);
        lenient().when(jobRepo.insert(anyLong(), anyString(), anyString())).thenReturn(42L);
    }

    private void runInline() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("submit：入库 + 预创建 sink（先于执行线程的订阅可回放）+ 返回 jobId")
    void submitPreCreatesSink() {
        // executor 不实际执行（默认 mock 吞掉任务），只验证提交路径
        long jobId = service.submit("ds", 1L);

        assertThat(jobId).isEqualTo(42L);
        // 预创建的 sink 存在：后续 emit 不会因 sink 缺失被丢弃（emit 走 get 前 map 查找）
        sink.emit(jobId, new GenerationProgressEvent("sampling", 0, 1, "x"));
        verify(jobRepo).insert(eq(1L), eq("ds"), anyString());
        verify(jobRepo, never()).markRunning(anyLong());
    }

    @Test
    @DisplayName("submit：executor 拒绝时同步标记 FAILED 并抛 ServiceException，不留 pending 孤儿")
    void submitRejectedMarksFailed() {
        doThrow(new RejectedExecutionException("shutdown"))
                .when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.submit("ds", 1L))
                .isInstanceOf(ServiceException.class);

        var message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jobRepo).markFailed(eq(42L), message.capture());
        assertThat(message.getValue()).contains("拒绝");
        // sink 已 complete 释放：后续 emit 静默丢弃，不抛异常
        sink.emit(42L, new GenerationProgressEvent("x", 0, 1, "y"));
    }

    @Test
    @DisplayName("execute 成功：markRunning → 进度回调转发 sink 并落库 → markCompleted → 释放信号量")
    void executeSuccessLifecycle() {
        runInline();
        when(generator.generate(eq("ds"), eq(1L), any()))
                .thenAnswer(inv -> {
                    var listener = inv.getArgument(2, TestsetGeneratorService.ProgressListener.class);
                    listener.onProgress("kg_build", 5, 10, "主题抽取 5/10");
                    return dataset(100L);
                });

        service.submit("ds", 1L);

        verify(jobRepo).markRunning(42L);
        verify(jobRepo).markCompleted(42L, 100L);
        var progressJson = ArgumentCaptor.forClass(String.class);
        verify(jobRepo).updateProgress(eq(42L), progressJson.capture());
        assertThat(progressJson.getValue()).contains("kg_build").contains("5");
        // 信号量已释放（finally 路径）
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("execute 信号量超时：markFailed 且不触发生成")
    void executeSemaphoreTimeout() {
        runInline();
        semaphore.drainPermits(); // 0 许可
        props.getRunner().setAcquireTimeoutSeconds(0);

        service.submit("ds", 1L);

        var message = ArgumentCaptor.forClass(String.class);
        verify(jobRepo).markFailed(eq(42L), message.capture());
        assertThat(message.getValue()).contains("并发上限");
        verify(generator, never()).generate(anyString(), anyLong(), any());
        verify(jobRepo, never()).markCompleted(anyLong(), anyLong());
    }

    @Test
    @DisplayName("execute 生成异常：markFailed 携带错误信息并释放信号量")
    void executeFailureMarksFailed() {
        runInline();
        when(generator.generate(anyString(), anyLong(), any()))
                .thenThrow(new IllegalStateException("LLM 端点不可用"));

        service.submit("ds", 1L);

        var message = ArgumentCaptor.forClass(String.class);
        verify(jobRepo).markFailed(eq(42L), message.capture());
        assertThat(message.getValue()).contains("LLM 端点不可用");
        assertThat(semaphore.availablePermits()).isEqualTo(1); // 已释放
    }

    @Test
    @DisplayName("getJob：不存在抛 ServiceException（404 语义）")
    void getJobNotFound() {
        when(jobRepo.find(anyLong())).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.getJob(99L))
                .isInstanceOf(ServiceException.class);
    }

    private static EvaluationDataset dataset(long id) {
        return new EvaluationDataset(id, "ds", "desc", 1, "ragas_kg", "judge", 0, null, null, null);
    }
}
