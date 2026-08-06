package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatUsageTracker 测试 — 验证用量记录通过消息总线发布（Phase C Step 1）。
 */
@ExtendWith(MockitoExtension.class)
class ChatUsageTrackerTest {

    @Mock
    private MessageBus messageBus;

    @Mock
    private ChatResponse aiResponse;

    @Mock
    private ChatResponseMetadata metadata;

    @Mock
    private Usage usage;

    @SuppressWarnings("unchecked")
    private MessageEnvelope<UsagePayload> captureSent() {
        ArgumentCaptor<MessageEnvelope<UsagePayload>> captor =
                ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(messageBus).send(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("recordUsage(aiResponse)")
    class RecordUsageWithResponse {

        @Test
        @DisplayName("usage 非空时发布含 token 数的 UsagePayload")
        void test_recordUsage_withResponse_publishesPayload() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getPromptTokens()).thenReturn(100);
            when(usage.getCompletionTokens()).thenReturn(50);
            when(usage.getTotalTokens()).thenReturn(150);

            ChatUsageTracker tracker = new ChatUsageTracker(messageBus, null);
            tracker.recordUsage("conv-1", "candidate-a", aiResponse, 200L);

            MessageEnvelope<UsagePayload> env = captureSent();
            assertThat(env.topic()).isEqualTo("chat_usage_record");
            assertThat(env.deduplicationKey()).startsWith("conv-1:candidate-a:");
            UsagePayload p = env.payload();
            assertThat(p.conversationId()).isEqualTo("conv-1");
            assertThat(p.candidateId()).isEqualTo("candidate-a");
            assertThat(p.promptTokens()).isEqualTo(100L);
            assertThat(p.completionTokens()).isEqualTo(50L);
            assertThat(p.totalTokens()).isEqualTo(150L);
            assertThat(p.durationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("usage 为 null 时仍发布记录，token 三字段填 -1")
        void test_recordUsage_withResponse_nullUsageStillPublishes() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(null);

            ChatUsageTracker tracker = new ChatUsageTracker(messageBus, null);
            tracker.recordUsage("conv-1", "candidate-a", aiResponse, 200L);

            MessageEnvelope<UsagePayload> env = captureSent();
            assertThat(env.payload().promptTokens()).isEqualTo(-1L);
            assertThat(env.payload().completionTokens()).isEqualTo(-1L);
            assertThat(env.payload().totalTokens()).isEqualTo(-1L);
            assertThat(env.payload().durationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("aiResponse 为 null 时仍发布记录（token -1）")
        void test_recordUsage_withResponse_nullResponseStillPublishes() {
            ChatUsageTracker tracker = new ChatUsageTracker(messageBus, null);
            tracker.recordUsage("conv-1", "candidate-a", null, 200L);

            MessageEnvelope<UsagePayload> env = captureSent();
            assertThat(env.payload().promptTokens()).isEqualTo(-1L);
            assertThat(env.payload().durationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("messageBus.send 抛异常时不向外传播，且 chat.usage.publish_failed +1（R4）")
        void test_recordUsage_withResponse_swallowsException() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getPromptTokens()).thenReturn(100);
            when(usage.getCompletionTokens()).thenReturn(50);
            when(usage.getTotalTokens()).thenReturn(150);
            doThrow(new RuntimeException("bus error")).when(messageBus).send(any());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatUsageTracker tracker = new ChatUsageTracker(messageBus, registry);
            // Should not throw
            tracker.recordUsage("conv-1", "candidate-a", aiResponse, 200L);

            assertThat(registry.find("chat.usage.publish_failed").counter()).isNotNull();
            assertThat(registry.find("chat.usage.publish_failed").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("recordUsage(durationOnly)")
    class RecordUsageDurationOnly {

        @Test
        @DisplayName("无 AI 响应时发布 token 三字段填 -1 的记录")
        void test_recordUsage_durationOnly_usesDefaults() {
            ChatUsageTracker tracker = new ChatUsageTracker(messageBus, null);
            tracker.recordUsage("conv-1", "candidate-a", 200L);

            MessageEnvelope<UsagePayload> env = captureSent();
            assertThat(env.topic()).isEqualTo("chat_usage_record");
            assertThat(env.payload().promptTokens()).isEqualTo(-1L);
            assertThat(env.payload().completionTokens()).isEqualTo(-1L);
            assertThat(env.payload().totalTokens()).isEqualTo(-1L);
            assertThat(env.payload().durationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("messageBus.send 抛异常时不向外传播，且 counter +1（R4）")
        void test_recordUsage_durationOnly_swallowsException() {
            doThrow(new RuntimeException("bus error")).when(messageBus).send(any());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatUsageTracker tracker = new ChatUsageTracker(messageBus, registry);
            // Should not throw
            tracker.recordUsage("conv-1", "candidate-a", 200L);

            assertThat(registry.find("chat.usage.publish_failed").counter()).isNotNull();
            assertThat(registry.find("chat.usage.publish_failed").counter().count()).isEqualTo(1.0);
        }
    }
}
