package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.usage.UsageContext;
import com.smart.rag.infrastructure.llm.usage.UsageSample;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UsageRecordingChatModel 单测 — 统一采集装饰器的行为契约：
 * 阻塞提取 / 流式轮末求和一次性发布 / 无 usage 估算 / 失败采样 / 跨轮累计（护栏连续性）。
 */
@ExtendWith(MockitoExtension.class)
class UsageRecordingChatModelTest {

    @Mock
    private ChatModel delegate;

    private final List<UsageSample> samples = new ArrayList<>();

    private UsageRecordingChatModel model;

    @BeforeEach
    void setUp() {
        samples.clear();
        model = new UsageRecordingChatModel(delegate,
            new UsageContext(7L, "candidate-a", UsageScene.CHAT, "conv-1"),
            samples::add);
    }

    private static ChatResponse responseWithUsage(int prompt, int completion) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(prompt, completion, prompt + completion))
            .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))), metadata);
    }

    private static ChatResponse plainResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("call: 响应带真实 usage → 采样真实 token，estimated=false，success=true")
    void callPublishesRealUsage() {
        when(delegate.call(any(Prompt.class))).thenReturn(responseWithUsage(100, 50));

        model.call(new Prompt(new UserMessage("hello")));

        assertThat(samples).hasSize(1);
        UsageSample sample = samples.get(0);
        assertThat(sample.promptTokens()).isEqualTo(100L);
        assertThat(sample.completionTokens()).isEqualTo(50L);
        assertThat(sample.estimated()).isFalse();
        assertThat(sample.success()).isTrue();
        assertThat(sample.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(model.getTotalTokens()).isEqualTo(150L);
    }

    @Test
    @DisplayName("call: 响应无 usage → 字符数/4 估算，estimated=true")
    void callEstimatesWhenUsageMissing() {
        // prompt 12 字符 + 输出 8 字符 → 估算 3 + 2
        when(delegate.call(any(Prompt.class))).thenReturn(plainResponse("12345678"));

        model.call(new Prompt(new UserMessage("abcdefghijkl")));

        assertThat(samples).hasSize(1);
        UsageSample sample = samples.get(0);
        assertThat(sample.promptTokens()).isEqualTo(3L);
        assertThat(sample.completionTokens()).isEqualTo(2L);
        assertThat(sample.estimated()).isTrue();
        assertThat(model.isEstimationUsed()).isTrue();
    }

    @Test
    @DisplayName("call: delegate 抛异常 → 采样 success=false、token 未知，异常重抛")
    void callRecordsFailureAndRethrows() {
        when(delegate.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> model.call(new Prompt(new UserMessage("hello"))))
            .isInstanceOf(IllegalStateException.class);

        assertThat(samples).hasSize(1);
        UsageSample sample = samples.get(0);
        assertThat(sample.success()).isFalse();
        assertThat(sample.promptTokens()).isNull();
        assertThat(sample.completionTokens()).isNull();
        assertThat(sample.estimated()).isFalse();
    }

    @Test
    @DisplayName("stream: 多个轮末 usage 帧 → 完成时求和一次性发布")
    void streamSumsRoundUsageAndPublishesOnce() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
            responseWithUsage(100, 50),
            responseWithUsage(200, 80)));

        model.stream(new Prompt(new UserMessage("hello"))).blockLast();

        assertThat(samples).hasSize(1);
        UsageSample sample = samples.get(0);
        assertThat(sample.promptTokens()).isEqualTo(300L);
        assertThat(sample.completionTokens()).isEqualTo(130L);
        assertThat(sample.success()).isTrue();
        assertThat(sample.estimated()).isFalse();
    }

    @Test
    @DisplayName("stream: 全程无 usage 正常完成 → 字符估算兜底（与阻塞路径同语义）")
    void streamEstimatesWhenNoUsageArrives() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
            plainResponse("12345678")));

        model.stream(new Prompt(new UserMessage("abcdefghijkl"))).blockLast();

        assertThat(samples).hasSize(1);
        UsageSample sample = samples.get(0);
        assertThat(sample.promptTokens()).isEqualTo(3L);
        assertThat(sample.completionTokens()).isEqualTo(2L);
        assertThat(sample.estimated()).isTrue();
        assertThat(sample.success()).isTrue();
    }

    @Test
    @DisplayName("stream: 错误终态 → success=false，token 记未知")
    void streamRecordsFailure() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("boom")));

        assertThatThrownBy(() -> model.stream(new Prompt(new UserMessage("hello"))).blockLast())
            .isInstanceOf(IllegalStateException.class);

        assertThat(samples).hasSize(1);
        UsageSample sample = samples.get(0);
        assertThat(sample.success()).isFalse();
        assertThat(sample.promptTokens()).isNull();
        assertThat(sample.completionTokens()).isNull();
    }

    @Test
    @DisplayName("跨调用累计（护栏连续性）：多次 call 的 token 持续累加到实例 totals")
    void totalsAccumulateAcrossCalls() {
        when(delegate.call(any(Prompt.class)))
            .thenReturn(responseWithUsage(100, 50))
            .thenReturn(responseWithUsage(30, 20));

        model.call(new Prompt(new UserMessage("a")));
        model.call(new Prompt(new UserMessage("b")));

        assertThat(samples).hasSize(2);
        assertThat(model.getTotalPromptTokens()).isEqualTo(130L);
        assertThat(model.getTotalCompletionTokens()).isEqualTo(70L);
        assertThat(model.getTotalTokens()).isEqualTo(200L);
    }

    @Test
    @DisplayName("上下文透传：采样携带装配时绑定的归因上下文")
    void sampleCarriesBoundContext() {
        when(delegate.call(any(Prompt.class))).thenReturn(responseWithUsage(1, 1));

        model.call(new Prompt(new UserMessage("x")));

        UsageContext context = samples.get(0).context();
        assertThat(context.userId()).isEqualTo(7L);
        assertThat(context.candidateId()).isEqualTo("candidate-a");
        assertThat(context.scene()).isEqualTo(UsageScene.CHAT);
        assertThat(context.conversationId()).isEqualTo("conv-1");
    }
}
