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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * ChatMessagePublisher 测试 — token（@Nullable，null 表未知）与 durationMs 经 payload 落库；
 * 仅 outbox INSERT 失败时记 {@code chat.save.publish_failed} 告警计数。
 */
@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

    @Mock
    private MessageBus messageBus;

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
        @DisplayName("send 成功：envelope 含正确 topic / dedupKey；payload 携带 token 与 durationMs")
        void sendSuccessPublishesEnvelope() {
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", 150, 200L);

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
            assertThat(p.durationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("token 未知（null）时 payload.totalTokens=null（不落 -1 哨兵）")
        void sendSuccessWithNullTokensKeepsNull() {
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", null, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.payload().totalTokens()).isNull();
            assertThat(env.payload().durationMs()).isEqualTo(200L);
        }

        @Test
        @DisplayName("outbox INSERT 失败（send 抛 MessagingException）→ chat.save.publish_failed +1，不向上传播")
        void outboxInsertFailureCountsAlert() {
            doThrow(new MessagingException(MessagingErrorCode.OUTBOX_INSERT_FAILED, "db down"))
                    .when(messageBus).send(any());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, registry);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", 150, 200L);

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
