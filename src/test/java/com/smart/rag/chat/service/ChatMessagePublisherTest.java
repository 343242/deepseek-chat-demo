package com.smart.rag.chat.service;

import com.smart.rag.common.util.ChecksumUtils;
import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
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
 * ChatMessagePublisher 测试 — 验证消息保存经 outbox 托管的消息总线发布（child 2：R2 移除同步降级，
 * 仅 outbox INSERT 失败时记 {@code chat.save.publish_failed} 告警计数）。
 */
@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

    @Mock
    private MessageBus messageBus;

    @Mock
    private ChatResponse aiResponse;

    @Mock
    private ChatResponseMetadata metadata;

    @Mock
    private Usage usage;

    @SuppressWarnings("unchecked")
    private MessageEnvelope<ChatMessagePayload> captureSent() {
        ArgumentCaptor<MessageEnvelope<ChatMessagePayload>> captor =
                ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(messageBus).send(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("publishMessageSave")
    class PublishMessageSave {

        @Test
        @DisplayName("send 成功：消息 envelope 含正确 topic / payload / dedupKey（sha256(userMessage)）")
        void sendSuccessPublishesEnvelope() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getTotalTokens()).thenReturn(150);

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.topic()).isEqualTo("chat_message_save");
            assertThat(env.deduplicationKey())
                    .isEqualTo("conv-1:" + ChecksumUtils.sha256Hex("hello"));

            ChatMessagePayload p = env.payload();
            assertThat(p.conversationId()).isEqualTo("conv-1");
            assertThat(p.userMessage()).isEqualTo("hello");
            assertThat(p.assistantContent()).isEqualTo("Hi there!");
            assertThat(p.candidateId()).isEqualTo("candidate-a");
            assertThat(p.totalTokens()).isEqualTo(150L);
        }

        @Test
        @DisplayName("aiResponse 为 null 时 totalTokens=-1，仍正常发布")
        void sendSuccessWithNullAiResponseUsesNegOne() {
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", null, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.payload().totalTokens()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("usage 为 null 时 totalTokens=-1，仍正常发布")
        void sendSuccessWithNullUsageUsesNegOne() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(null);

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.payload().totalTokens()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("outbox INSERT 失败（send 抛 MessagingException）→ chat.save.publish_failed +1，不向上传播")
        void outboxInsertFailureCountsAlert() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getTotalTokens()).thenReturn(150);
            doThrow(new MessagingException(MessagingErrorCode.OUTBOX_INSERT_FAILED, "db down"))
                    .when(messageBus).send(any());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, registry);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            assertThat(registry.find("chat.save.publish_failed").counter()).isNotNull();
            assertThat(registry.find("chat.save.publish_failed").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("MeterRegistry 缺失（registry=null）时 INSERT 失败不抛、仅记日志")
        void outboxInsertFailureWithoutRegistryDoesNotThrow() {
            doThrow(new MessagingException(MessagingErrorCode.OUTBOX_INSERT_FAILED, "db down"))
                    .when(messageBus).send(any());

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", null, 200L);   // 不抛
        }
    }
}
