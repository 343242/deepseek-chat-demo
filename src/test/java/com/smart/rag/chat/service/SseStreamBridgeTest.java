package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.mode.Reference;
import com.smart.rag.mode.StreamFrame;
import com.smart.rag.mode.WorkspaceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SseStreamBridge 帧分发 + 收尾帧决策 + doOnComplete 时序锚点验证。
 * <p>
 * 核心验证点：
 * <ol>
 *   <li>REASONING 帧发 {@code event:reasoning}，CONTENT 帧发默认 data 帧，时序保留</li>
 *   <li>Agent 流式 complete 发 references/agentMetadata/fallback 三帧，且顺序正确</li>
 *   <li>标准模式仅 references 帧；未降级无 fallback 帧</li>
 *   <li>content onError 走结构化 error 帧（含 attempted），不发收尾帧</li>
 *   <li>doOnComplete 副作用先于 subscribe onComplete 回调（整个设计的时间锚点）</li>
 * </ol>
 */
@DisplayName("SseStreamBridge 帧分发与收尾时序")
class SseStreamBridgeTest {

    private final SseStreamBridge bridge = new SseStreamBridge();

    /**
     * 捕获所有 {@code send(SseEventBuilder)} 下发的 SSE 文本。
     * <p>
     * {@code send(SseEventBuilder)} 非 final，可重写；SseStreamBridge 全部帧均经此方法。
     * builder.build() 返回多行 SSE 文本片段（{@code event:xxx} / {@code data:...}），
     * 拼接后即为完整 SSE 输出。
     */
    private static String captureSse(SseEmitter emitter) {
        return (emitter instanceof CapturingSseEmitter c) ? c.captured.toString() : "";
    }

    /** 捕获 send(SseEventBuilder) 调用的 SseEmitter 子类。 */
    static class CapturingSseEmitter extends SseEmitter {
        final StringBuilder captured = new StringBuilder();

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            // builder.build() 返回 Set<DataWithMediaType>，每个元素是 SSE 文本一行
            Set<ResponseBodyEmitter.DataWithMediaType> items = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType item : items) {
                captured.append(item.getData());
            }
        }
    }

    private static CapturingSseEmitter capturing() {
        return new CapturingSseEmitter();
    }

    @Test
    @DisplayName("REASONING 帧发 event:reasoning，CONTENT 帧发默认 data 帧，时序保留（reasoning 先于 content）")
    void reasoningAndContentFramesDispatchedSeparately() {
        CapturingSseEmitter emitter = capturing();
        bridge.subscribe(emitter, Flux.just(
            StreamFrame.reasoning("先思考"),
            StreamFrame.content("再作答")), null);

        String all = captureSse(emitter);
        assertThat(all).contains("event:reasoning", "先思考");
        assertThat(all).contains("data:再作答");
        assertThat(all).doesNotContain("event:content");  // content 是默认帧，无 event 行
        // reasoning 帧先于 content 帧
        assertThat(all.indexOf("先思考")).isLessThan(all.indexOf("再作答"));
    }

    @Test
    @DisplayName("纯 reasoning 流（无 content）也能正常下发")
    void reasoningOnlyStream() {
        CapturingSseEmitter emitter = capturing();
        bridge.subscribe(emitter, Flux.just(StreamFrame.reasoning("仅思考")), null);

        String all = captureSse(emitter);
        assertThat(all).contains("event:reasoning", "仅思考");
    }

    @Test
    @DisplayName("Agent 流式 complete 发 references + agentMetadata + fallback 三帧（顺序：references → agentMetadata → fallback）")
    void agentStreamEmitsAllTailFrames() {
        AtomicReference<List<Reference>> refsRef = new AtomicReference<>(List.of(
            new Reference(1, "c1", "d1", "f.pdf", null, 0.9, "hybridSearch", "片段")));
        AtomicReference<Map<String, Object>> metaRef = new AtomicReference<>(new LinkedHashMap<>(Map.of(
            "intent", "SEARCH", "confidence", 0.9, "retrievalRounds", 2)));
        AtomicReference<FallbackMeta> fbRef = new AtomicReference<>(new FallbackMeta("model-a", true));

        CapturingSseEmitter emitter = capturing();
        bridge.subscribe(emitter,
            Flux.just(StreamFrame.content("chunk1"), StreamFrame.content("chunk2")),
            new SseStreamBridge.SseTailFrames(refsRef, metaRef, fbRef, List.of("model-b")));

        String all = captureSse(emitter);
        assertThat(all).contains("chunk1", "chunk2");
        assertThat(all).contains("event:references");
        assertThat(all).contains("event:agentMetadata");
        assertThat(all).contains("event:fallback");
        assertThat(all.indexOf("event:references")).isLessThan(all.indexOf("event:agentMetadata"));
        assertThat(all.indexOf("event:agentMetadata")).isLessThan(all.indexOf("event:fallback"));
    }

    @Test
    @DisplayName("标准模式（仅 refsRef）只发 references 帧，不发 agentMetadata/fallback")
    void standardStreamEmitsOnlyReferences() {
        AtomicReference<List<Reference>> refsRef = new AtomicReference<>(List.of(
            new Reference(1, "c1", "d1", "f.pdf", null, 0.5, null, null)));

        CapturingSseEmitter emitter = capturing();
        bridge.subscribe(emitter, Flux.just(StreamFrame.content("hi")),
            new SseStreamBridge.SseTailFrames(refsRef, null, null, null));

        String all = captureSse(emitter);
        assertThat(all).contains("event:references");
        assertThat(all).doesNotContain("event:agentMetadata", "event:fallback");
    }

    @Test
    @DisplayName("未降级（fallback=false）不发 fallback 帧")
    void noFallbackNoFallbackFrame() {
        AtomicReference<FallbackMeta> fbRef = new AtomicReference<>(new FallbackMeta("model-a", false));

        CapturingSseEmitter emitter = capturing();
        bridge.subscribe(emitter, Flux.just(StreamFrame.content("ok")),
            new SseStreamBridge.SseTailFrames(null, null, fbRef, null));

        assertThat(captureSse(emitter)).doesNotContain("event:fallback");
    }

    @Test
    @DisplayName("content onError 走结构化 error 帧（含 attempted），不发收尾帧")
    void errorStreamEmitsErrorFrameOnly() {
        AtomicReference<List<Reference>> refsRef = new AtomicReference<>(List.of(
            new Reference(1, "c", "d", "f", null, 0.5, null, null)));

        CapturingSseEmitter emitter = capturing();
        bridge.subscribe(emitter, Flux.error(new RuntimeException("boom")),
            new SseStreamBridge.SseTailFrames(refsRef, null, null, List.of("model-x")));

        String all = captureSse(emitter);
        assertThat(all).contains("event:error");
        assertThat(all).contains("boom");
        assertThat(all).contains("attempted");
        assertThat(all).doesNotContain("event:references");
    }

    @Test
    @DisplayName("doOnComplete 副作用先于 subscribe onComplete 回调（时序锚点）：retrievalRounds 在回调时已刷新")
    void doOnCompleteRefreshesBeforeOnComplete() {
        WorkspaceInfo ws = mock(WorkspaceInfo.class);
        when(ws.getRetrievalRound()).thenReturn(3);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("intent", "SEARCH");

        AtomicBoolean refreshedBeforeOnComplete = new AtomicBoolean(false);

        // 模拟 ChatServiceImpl.chatStream 的外层 doOnComplete 挂载位置
        Flux<StreamFrame> stream = Flux.just(StreamFrame.content("a"), StreamFrame.content("b"))
            .doOnComplete(() -> meta.put("retrievalRounds", ws.getRetrievalRound()));

        stream.subscribe(
            t -> {},
            e -> {},
            () -> refreshedBeforeOnComplete.set(Integer.valueOf(3).equals(meta.get("retrievalRounds")))
        );

        assertThat(refreshedBeforeOnComplete.get())
            .as("doOnComplete 必须在 subscribe onComplete 回调前刷新 retrievalRounds（reactor 操作符拦截语义）")
            .isTrue();
        assertThat(meta.get("retrievalRounds")).isEqualTo(3);
    }
}
