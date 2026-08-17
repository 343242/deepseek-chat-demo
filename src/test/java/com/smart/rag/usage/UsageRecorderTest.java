package com.smart.rag.usage;

import com.smart.rag.infrastructure.llm.usage.UsageContext;
import com.smart.rag.infrastructure.llm.usage.UsageSample;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * UsageRecorder 单测 — 端口实现：payload 组装（含 totalTokens 派生）+ eventId 幂等键 + 绝不抛出。
 */
@ExtendWith(MockitoExtension.class)
class UsageRecorderTest {

    @Mock
    private MessageBus messageBus;

    private SimpleMeterRegistry meterRegistry;

    private UsageRecorder recorder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        recorder = new UsageRecorder(messageBus, meterRegistry);
    }

    @Test
    @DisplayName("accept: 发布 UsageEventPayload 到 usage_event_record，eventId 兼作幂等键")
    void acceptPublishesPayloadWithEventIdDedup() {
        recorder.accept(new UsageSample(
            new UsageContext(7L, "candidate-a", UsageScene.CHAT, "conv-1"),
            100L, 50L, false, true, 200L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageEnvelope<UsageEventPayload>> captor =
            ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(messageBus).send(captor.capture());

        MessageEnvelope<UsageEventPayload> envelope = captor.getValue();
        assertThat(envelope.topic()).isEqualTo("usage_event_record");
        assertThat(envelope.deduplicationKey()).isEqualTo(envelope.payload().eventId());
        assertThat(envelope.payload().userId()).isEqualTo(7L);
        assertThat(envelope.payload().scene()).isEqualTo("CHAT");
        assertThat(envelope.payload().conversationId()).isEqualTo("conv-1");
        assertThat(envelope.payload().candidateId()).isEqualTo("candidate-a");
        assertThat(envelope.payload().promptTokens()).isEqualTo(100L);
        assertThat(envelope.payload().completionTokens()).isEqualTo(50L);
        assertThat(envelope.payload().totalTokens()).isEqualTo(150L);
        assertThat(envelope.payload().success()).isTrue();
        assertThat(envelope.payload().durationMs()).isEqualTo(200L);
    }

    @Test
    @DisplayName("accept: token 未知(null)时 totalTokens 亦为 null（不做 0 污染）")
    void acceptKeepsUnknownTokensNull() {
        recorder.accept(new UsageSample(
            new UsageContext(7L, "candidate-a", UsageScene.AGENT, null),
            null, null, false, false, 50L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageEnvelope<UsageEventPayload>> captor =
            ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(messageBus).send(captor.capture());

        UsageEventPayload payload = captor.getValue().payload();
        assertThat(payload.promptTokens()).isNull();
        assertThat(payload.completionTokens()).isNull();
        assertThat(payload.totalTokens()).isNull();
    }

    @Test
    @DisplayName("accept: 发布失败绝不抛出，仅计 usage.publish_failed")
    void acceptSwallowsPublishFailure() {
        doThrow(new RuntimeException("outbox down")).when(messageBus).send(any());

        assertThatCode(() -> recorder.accept(new UsageSample(
            new UsageContext(7L, "candidate-a", UsageScene.CHAT, "conv-1"),
            1L, 1L, false, true, 10L))).doesNotThrowAnyException();

        assertThat(meterRegistry.counter("usage.publish_failed").count()).isEqualTo(1.0);
    }
}
