package com.smart.rag.evaluation.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvaluationProgressSink} 订阅语义测试：sink 缺失（run 已结束）返回空流，
 * 不重建永不 complete 的 sink——SSE 订阅竞态防护（镜像 GenerationProgressSink 语义）。
 */
@DisplayName("评测进度 sink（订阅不创建）")
class EvaluationProgressSinkTest {

    @Test
    @DisplayName("complete 后订阅：空流立即完成（不重建 entry，连接不挂到超时）")
    void subscribeAfterCompleteReturnsEmptyFlux() {
        var sink = new EvaluationProgressSink();
        sink.getOrCreate(1L);
        sink.complete(1L);

        assertThat(sink.isActive(1L)).isFalse();
        assertThat(sink.subscribe(1L).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("从未存在的 runId 订阅：同样得到空流")
    void subscribeUnknownRunReturnsEmptyFlux() {
        var sink = new EvaluationProgressSink();

        assertThat(sink.subscribe(99L).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("活 sink 订阅：回放已 emit 的事件")
    void subscribeWhileActiveReplaysEvents() {
        var sink = new EvaluationProgressSink();
        sink.getOrCreate(2L);
        sink.emit(2L, EvaluationProgressEvent.success(2L, 1, 10, 1, 0, 5L, 100L));

        var collected = sink.subscribe(2L).take(1).collectList().block();

        assertThat(collected).hasSize(1);
        assertThat(sink.isActive(2L)).isTrue();
        sink.complete(2L);
    }
}
