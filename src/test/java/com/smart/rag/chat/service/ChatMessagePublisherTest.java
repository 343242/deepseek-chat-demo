package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.codec.digest.DigestUtils;
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
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatMessagePublisher 测试 — 验证消息保存通过消息总线发布 + 同步降级有限重试（Phase C Step 2 / Phase D D-4）。
 */
@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

    /** 测试用零退避，避免单测真实 Thread.sleep。生产退避见 {@link ChatMessagePublisher#DEFAULT_BACKOFF_MS}。 */
    private static final long[] ZERO_BACKOFF = {0, 0, 0};

    @Mock
    private MessageBus messageBus;

    @Mock
    private ChatConversationHelper conversationHelper;

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

    private void busDown() {
        doThrow(new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "bus down"))
                .when(messageBus).send(any());
    }

    @Nested
    @DisplayName("publishMessageSave")
    class PublishMessageSave {

        @Test
        @DisplayName("send 成功：消息 envelope 含正确 topic / payload / dedupKey（md5(userMessage)）")
        void sendSuccessPublishesEnvelope() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getTotalTokens()).thenReturn(150);

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.topic()).isEqualTo("chat_message_save");
            assertThat(env.deduplicationKey())
                    .isEqualTo("conv-1:" + DigestUtils.md5Hex("hello"));

            ChatMessagePayload p = env.payload();
            assertThat(p.conversationId()).isEqualTo("conv-1");
            assertThat(p.userMessage()).isEqualTo("hello");
            assertThat(p.assistantContent()).isEqualTo("Hi there!");
            assertThat(p.candidateId()).isEqualTo("candidate-a");
            assertThat(p.totalTokens()).isEqualTo(150L);

            // send 成功不应走 fallback
            verifyNoInteractions(conversationHelper);
        }

        @Test
        @DisplayName("aiResponse 为 null 时 totalTokens=-1，仍正常发布")
        void sendSuccessWithNullAiResponseUsesNegOne() {
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", null, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.payload().totalTokens()).isEqualTo(-1L);
            verifyNoInteractions(conversationHelper);
        }

        @Test
        @DisplayName("usage 为 null 时 totalTokens=-1，仍正常发布")
        void sendSuccessWithNullUsageUsesNegOne() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(null);

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            MessageEnvelope<ChatMessagePayload> env = captureSent();
            assertThat(env.payload().totalTokens()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("MessagingException 触发同步降级：调 saveMessagesAndNotify 保留全语义")
        void messagingExceptionFallsBackToSyncSave() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getTotalTokens()).thenReturn(150);
            busDown();

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            // 同步降级：传入提取后的 totalTokens（150）；helper mock 正常返回 → saveWithBoundedRetry 调 1 次即成
            verify(conversationHelper).saveMessagesAndNotify(
                    eq("conv-1"), eq("hello"), eq("Hi there!"),
                    eq("candidate-a"), eq(150), eq(200L));
        }

        @Test
        @DisplayName("MessagingException 降级时 totalTokens 与 publisher 提取逻辑一致（aiResponse=null → -1）")
        void messagingExceptionFallbackWithNullAiResponse() {
            busDown();

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper, null);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", null, 200L);

            verify(conversationHelper).saveMessagesAndNotify(
                    eq("conv-1"), eq("hello"), eq("Hi there!"),
                    eq("candidate-a"), eq(-1), eq(200L));
        }
    }

    @Nested
    @DisplayName("saveWithBoundedRetry（Phase D D-4：同步降级有限重试）")
    class FallbackBoundedRetry {

        @Test
        @DisplayName("瞬时 DB 异常（DataAccessException）重试后成功：调 2 次，不告警")
        void transientDbFailureRetriesThenSucceeds() {
            busDown();
            // 第 1 次抛瞬时 DB 异常，第 2 次成功
            doThrow(new DataAccessException("conn blip") {})
                    .doNothing()
                    .when(conversationHelper).saveMessagesAndNotify(
                            any(), any(), any(), any(), anyInt(), anyLong());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatMessagePublisher publisher = new ChatMessagePublisher(
                    messageBus, conversationHelper, registry, ZERO_BACKOFF);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!", "candidate-a", null, 200L);

            verify(conversationHelper, times(2)).saveMessagesAndNotify(
                    any(), any(), any(), any(), anyInt(), anyLong());
            assertThat(registry.find("chat.save.fallback_failed").counter()).isNull();
        }

        @Test
        @DisplayName("TransactionSystemException（提交期 infra 失败）同为可重试瞬时异常：重试后成功")
        void transactionSystemExceptionIsRetriable() {
            busDown();
            doThrow(new TransactionSystemException("commit infra failed"))
                    .doNothing()
                    .when(conversationHelper).saveMessagesAndNotify(
                            any(), any(), any(), any(), anyInt(), anyLong());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatMessagePublisher publisher = new ChatMessagePublisher(
                    messageBus, conversationHelper, registry, ZERO_BACKOFF);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!", "candidate-a", null, 200L);

            verify(conversationHelper, times(2)).saveMessagesAndNotify(
                    any(), any(), any(), any(), anyInt(), anyLong());
            assertThat(registry.find("chat.save.fallback_failed").counter()).isNull();
        }

        @Test
        @DisplayName("DB 硬故障重试耗尽：调满 3 次、不向调用方传播、chat.save.fallback_failed +1")
        void hardDbFailureExhaustsAndAlerts() {
            busDown();
            doThrow(new DataAccessException("db down") {})
                    .when(conversationHelper).saveMessagesAndNotify(
                            any(), any(), any(), any(), anyInt(), anyLong());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatMessagePublisher publisher = new ChatMessagePublisher(
                    messageBus, conversationHelper, registry, ZERO_BACKOFF);
            // 不抛出（降级路径不向 processResult / executeStream 传播）
            publisher.publishMessageSave("conv-1", "hello", "Hi there!", "candidate-a", null, 200L);

            verify(conversationHelper, times(3)).saveMessagesAndNotify(
                    any(), any(), any(), any(), anyInt(), anyLong());
            assertThat(registry.find("chat.save.fallback_failed").counter()).isNotNull();
            assertThat(registry.find("chat.save.fallback_failed").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("非瞬时异常（RuntimeException）不重试：调 1 次、告警 +1")
        void nonTransientFailureDoesNotRetry() {
            busDown();
            doThrow(new IllegalStateException("logic bug"))
                    .when(conversationHelper).saveMessagesAndNotify(
                            any(), any(), any(), any(), anyInt(), anyLong());

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ChatMessagePublisher publisher = new ChatMessagePublisher(
                    messageBus, conversationHelper, registry, ZERO_BACKOFF);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!", "candidate-a", null, 200L);

            verify(conversationHelper, times(1)).saveMessagesAndNotify(
                    any(), any(), any(), any(), anyInt(), anyLong());
            assertThat(registry.find("chat.save.fallback_failed").counter()).isNotNull();
            assertThat(registry.find("chat.save.fallback_failed").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("告警计数降级：MeterRegistry 缺失时不抛、仅记日志")
        void alertsGracefullyWithoutRegistry() {
            busDown();
            doThrow(new DataAccessException("db down") {})
                    .when(conversationHelper).saveMessagesAndNotify(
                            any(), any(), any(), any(), anyInt(), anyLong());

            ChatMessagePublisher publisher = new ChatMessagePublisher(
                    messageBus, conversationHelper, null, ZERO_BACKOFF);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!", "candidate-a", null, 200L);

            verify(conversationHelper, times(3)).saveMessagesAndNotify(
                    any(), any(), any(), any(), anyInt(), anyLong());
        }
    }
}
