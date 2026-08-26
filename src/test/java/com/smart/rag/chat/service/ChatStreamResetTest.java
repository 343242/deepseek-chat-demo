package com.smart.rag.chat.service;

import com.smart.rag.chat.service.impl.ChatServiceImpl;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.mode.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.mode.StreamFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 流式降级 reset 标记注入测试（design llm-resilience-optimization WS5，AC6）：
 * <ul>
 *   <li>降级重入且前一模型已发帧 → 恰一个 reset 帧在新模型内容之前（from/to = candidateId）</li>
 *   <li>仅 reasoning 帧已发 → 同样触发 reset</li>
 *   <li>零帧失败（如探测超时）→ 无 reset</li>
 * </ul>
 * 捕获方式：mock SseStreamBridge 截获 subscribeCancellable 的 Flux 直接订阅。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("流式降级 reset 标记注入（WS5）")
class ChatStreamResetTest {

    private static final Long USER_ID = 42L;
    private static final String RAW_CONV_ID = "conv-abc";
    private static final String ISOLATED_CONV_ID = "u_42_conv-abc";
    private static final String PRIMARY = "model-a";
    private static final String FALLBACK = "model-b";

    @Mock private LlmClientRegistry llmRegistry;
    @Mock private FallbackEligibility fallbackEligibility;
    @Mock private com.smart.rag.chat.mode.ModeRouter modeRouter;
    @Mock private com.smart.rag.infrastructure.llm.adapter.ChatModelAssembler chatModelAssembler;
    @Mock private com.smart.rag.chat.service.ChatConversationHelper conversationHelper;
    @Mock private com.smart.rag.chat.service.ChatMessagePublisher chatMessagePublisher;
    @Mock private SseStreamBridge sseStreamBridge;
    @Mock private com.smart.rag.chat.context.RequestContextManager cagContextManager;
    @Mock private com.smart.rag.chat.context.CagProperties cagProperties;
    @Mock private com.smart.rag.chat.service.UserContextProvider userContextProvider;
    @Mock private com.smart.rag.team.service.TeamMembershipVerifier teamMembershipVerifier;
    @Mock private com.smart.rag.mode.ChatModeStrategy modeStrategy;

    private MockedStatic<com.smart.rag.common.util.ConversationIdUtil> conversationIdUtilMock;
    private final AtomicReference<Flux<StreamFrame>> captured = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        conversationIdUtilMock = mockStatic(com.smart.rag.common.util.ConversationIdUtil.class);
        when(userContextProvider.getCurrentUserId()).thenReturn(USER_ID);
        conversationIdUtilMock.when(() -> com.smart.rag.common.util.ConversationIdUtil.buildIsolatedId(USER_ID, RAW_CONV_ID))
            .thenReturn(ISOLATED_CONV_ID);
        when(modeRouter.route(any())).thenReturn(modeStrategy);
        when(modeStrategy.getMode()).thenReturn(com.smart.rag.mode.ChatMode.SIMPLE);
        when(cagProperties.isEnabled()).thenReturn(false);
        when(fallbackEligibility.isEligible(any(RuntimeException.class))).thenReturn(true);

        // 截获桥接：保存 stream flux 供测试直接订阅（不再走真实 SSE 发送）
        org.mockito.Mockito.doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return new SseEmitter();
        }).when(sseStreamBridge).bridge(any(), any(SseStreamBridge.SseTailFrames.class),
            any(), any(), any(), any(), any(String.class), any());

        when(llmRegistry.getChain(LlmCapability.CHAT)).thenReturn(List.of(
            mockChatClient(PRIMARY), mockChatClient(FALLBACK)));
    }

    @AfterEach
    void tearDown() {
        if (conversationIdUtilMock != null) conversationIdUtilMock.close();
    }

    private ChatCapable mockChatClient(String candidateId) {
        return org.mockito.Mockito.mock(ChatCapable.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "candidateId": case "modelName": return candidateId;
                case "providerId": return "test-provider";
                case "isAvailable": return true;
                case "supportsStreaming": return true;
                default: return null;
            }
        });
    }

    private ChatServiceImpl createService() {
        return new ChatServiceImpl(llmRegistry, fallbackEligibility, modeRouter, chatModelAssembler,
            conversationHelper, chatMessagePublisher, sseStreamBridge, cagContextManager,
            cagProperties, userContextProvider, teamMembershipVerifier,
            org.mockito.Mockito.mock(ActiveStreamRegistry.class),
            org.mockito.Mockito.mock(com.smart.rag.infrastructure.llm.metrics.LlmMetrics.class));
    }

    private ChatRequest request() {
        return new ChatRequest(PRIMARY, "hello", RAW_CONV_ID, false, "SIMPLE", false, null);
    }

    private List<StreamFrame> collect() {
        return captured.get().collectList().block(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("前一模型已发 content 帧失败 → 恰一个 reset(from=model-a,to=model-b) 先于新模型内容")
    void resetEmittedAfterContentFrames() {
        when(modeStrategy.executeStream(any())).thenReturn(
            new com.smart.rag.mode.StreamResult(
                Flux.just(StreamFrame.content("partial"))
                    .concatWith(Flux.error(new com.smart.rag.infrastructure.exception.RemoteException(
                        RemoteErrorCode.LLM_TRANSIENT_ERROR, "boom"))), null),
            new com.smart.rag.mode.StreamResult(Flux.just(StreamFrame.content("full-answer")), null));

        createService().chatStream(request());
        List<StreamFrame> frames = collect();

        assertThat(frames).isNotNull();
        assertThat(frames.stream().filter(StreamFrame::isReset)).hasSize(1);
        int resetIdx = frames.stream().map(StreamFrame::isReset).toList().indexOf(true);
        StreamFrame reset = frames.get(resetIdx);
        assertThat(reset.payload()).contains("\"from\": \"" + PRIMARY + "\"", "\"to\": \"" + FALLBACK + "\"");
        // reset 先于新模型内容，且前一模型 partial 帧在 reset 之前
        assertThat(frames.get(resetIdx - 1).payload()).isEqualTo("partial");
        assertThat(frames.get(frames.size() - 1).payload()).isEqualTo("full-answer");
    }

    @Test
    @DisplayName("仅 reasoning 帧已发 → 同样触发 reset（reasoning 缓冲同需清空）")
    void resetEmittedAfterReasoningOnlyFrames() {
        when(modeStrategy.executeStream(any())).thenReturn(
            new com.smart.rag.mode.StreamResult(
                Flux.just(StreamFrame.reasoning("thinking..."))
                    .concatWith(Flux.error(new com.smart.rag.infrastructure.exception.RemoteException(
                        RemoteErrorCode.LLM_TRANSIENT_ERROR, "boom"))), null),
            new com.smart.rag.mode.StreamResult(Flux.just(StreamFrame.content("answer")), null));

        createService().chatStream(request());
        List<StreamFrame> frames = collect();

        assertThat(frames.stream().filter(StreamFrame::isReset)).hasSize(1);
    }

    @Test
    @DisplayName("零帧失败（如探测超时）→ 无 reset 噪音")
    void noResetWhenNothingEmitted() {
        when(modeStrategy.executeStream(any())).thenReturn(
            new com.smart.rag.mode.StreamResult(
                Flux.error(new com.smart.rag.infrastructure.exception.RemoteException(
                    RemoteErrorCode.LLM_PROBE_TIMEOUT, "no first byte")), null),
            new com.smart.rag.mode.StreamResult(Flux.just(StreamFrame.content("answer")), null));

        createService().chatStream(request());
        List<StreamFrame> frames = collect();

        assertThat(frames).isNotNull();
        assertThat(frames.stream().filter(StreamFrame::isReset)).isEmpty();
        assertThat(frames).extracting(StreamFrame::payload).containsExactly("answer");
    }

    @Test
    @DisplayName("无降级（单模型成功）→ 无 reset")
    void noResetWithoutFallback() {
        when(modeStrategy.executeStream(any())).thenReturn(
            new com.smart.rag.mode.StreamResult(Flux.just(StreamFrame.content("ok")), null));

        createService().chatStream(request());
        List<StreamFrame> frames = collect();

        assertThat(frames.stream().filter(StreamFrame::isReset)).isEmpty();
    }
}
