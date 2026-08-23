package com.smart.rag.evaluation.controller;

import com.smart.rag.evaluation.dataset.DatasetExporter;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.testset.GenerationJobRecord;
import com.smart.rag.evaluation.testset.GenerationJobService;
import com.smart.rag.evaluation.testset.GenerationProgressSink;
import com.smart.rag.evaluation.testset.GenerationSseBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DatasetController} 异步生成与 SSE 端点测试（直接方法调用，
 * 无 MockMvc 先例，遵循项目以 Service 层为主的测试模式）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据集控制器（生成任务/SSE）")
class DatasetControllerTest {

    @Mock
    private DatasetRepository datasetRepo;

    @Mock
    private GenerationJobService jobService;

    @Mock
    private GenerationProgressSink progressSink;

    @Mock
    private GenerationSseBridge sseBridge;

    @Mock
    private DatasetExporter exporter;

    private DatasetController controller;

    @BeforeEach
    void setUp() {
        controller = new DatasetController(datasetRepo, jobService, progressSink,
                sseBridge, exporter);
        lenient().when(jobService.submit("ds", 1L)).thenReturn(42L);
    }

    @Nested
    @DisplayName("POST /generate")
    class Generate {

        @Test
        @DisplayName("成功提交：202 + jobId")
        void acceptsAndReturnsJobId() {
            var response = controller.generateDataset(Map.of("name", "ds", "userId", 1));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsEntry("jobId", 42L)
                    .containsEntry("status", "pending");
        }

        @Test
        @DisplayName("缺 userId：抛 ClientException（全局处理器转 400）")
        void rejectsMissingUserId() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> controller.generateDataset(Map.of("name", "ds")))
                    .isInstanceOf(com.smart.rag.infrastructure.exception.ClientException.class);
        }

        @Test
        @DisplayName("userId 类型错误（字符串）：400 而非 ClassCastException 500")
        void rejectsNonNumericUserId() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> controller.generateDataset(Map.of("name", "ds", "userId", "1")))
                    .isInstanceOf(com.smart.rag.infrastructure.exception.ClientException.class);
        }

        @Test
        @DisplayName("name 类型错误（数字）：回退默认名提交，不 500")
        void nonStringNameFallsBackToDefault() {
            var response = controller.generateDataset(Map.of("name", 123, "userId", 1));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            org.mockito.Mockito.verify(jobService).submit(
                    org.mockito.ArgumentMatchers.startsWith("dataset-"),
                    org.mockito.ArgumentMatchers.eq(1L));
        }
    }

    @Nested
    @DisplayName("GET /generate/{jobId}/events")
    class GenerationEvents {

        @Test
        @DisplayName("已结束任务走终态直连，不订阅 sink")
        void finishedJobUsesTerminalBridge() {
            var job = job("completed", 100L, null);
            when(jobService.getJob(42L)).thenReturn(job);
            var terminal = mock(SseEmitter.class);
            when(sseBridge.bridgeTerminated(job)).thenReturn(terminal);

            var emitter = controller.generationEvents(42L);

            assertThat(emitter).isSameAs(terminal);
            verify(progressSink, never()).getOrCreate(anyLong());
        }

        @Test
        @DisplayName("运行中任务订阅实时进度（不创建 sink，由 submit 预创建）")
        void runningJobBridgesLive() {
            when(jobService.getJob(42L)).thenReturn(job("running", null, null));
            var live = mock(SseEmitter.class);
            when(sseBridge.bridge(42L, progressSink)).thenReturn(live);

            var emitter = controller.generationEvents(42L);

            assertThat(emitter).isSameAs(live);
            verify(progressSink, never()).getOrCreate(anyLong());
        }

        @Test
        @DisplayName("任务不存在：ServiceException 上抛（全局处理器转 404）")
        void unknownJobPropagates() {
            when(jobService.getJob(99L)).thenThrow(
                    new com.smart.rag.infrastructure.exception.ServiceException(
                            com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode.NOT_FOUND,
                            "Generation job not found: 99"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.generationEvents(99L))
                    .isInstanceOf(com.smart.rag.infrastructure.exception.ServiceException.class);
        }
    }

    @Nested
    @DisplayName("GET /generate/{jobId}")
    class GetJob {

        @Test
        @DisplayName("返回任务状态记录")
        void returnsJobRecord() {
            var job = job("running", null, null);
            when(jobService.getJob(42L)).thenReturn(job);

            var response = controller.getGenerationJob(42L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(job);
        }
    }

    private static GenerationJobRecord job(String status, Long datasetId, String error) {
        var now = OffsetDateTime.now();
        return new GenerationJobRecord(42L, "ds", 1L, status, null, null, datasetId,
                error, now.minusMinutes(5), null, now.minusMinutes(10));
    }
}
