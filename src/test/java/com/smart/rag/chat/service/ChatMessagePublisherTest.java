package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatMessagePublisher 测试 — 验证消息保存通过消息总线发布 + 同步降级（Phase C Step 2）。
 */
@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

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

    @Nested
    @DisplayName("publishMessageSave")
    class PublishMessageSave {

        @Test
        @DisplayName("send 成功：消息 envelope 含正确 topic / payload / dedupKey（md5(userMessage)）")
        void sendSuccessPublishesEnvelope() {
            when(aiResponse.getMetadata()).thenReturn(metadata);
            when(metadata.getUsage()).thenReturn(usage);
            when(usage.getTotalTokens()).thenReturn(150);

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper);
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
            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper);
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

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper);
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
            doThrow(new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "bus down"))
                    .when(messageBus).send(any());

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", aiResponse, 200L);

            // 同步降级：传入提取后的 totalTokens（150）
            verify(conversationHelper).saveMessagesAndNotify(
                    eq("conv-1"), eq("hello"), eq("Hi there!"),
                    eq("candidate-a"), eq(150), eq(200L));
        }

        @Test
        @DisplayName("MessagingException 降级时 totalTokens 与 publisher 提取逻辑一致（aiResponse=null → -1）")
        void messagingExceptionFallbackWithNullAiResponse() {
            doThrow(new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "bus down"))
                    .when(messageBus).send(any());

            ChatMessagePublisher publisher = new ChatMessagePublisher(messageBus, conversationHelper);
            publisher.publishMessageSave("conv-1", "hello", "Hi there!",
                    "candidate-a", null, 200L);

            verify(conversationHelper).saveMessagesAndNotify(
                    eq("conv-1"), eq("hello"), eq("Hi there!"),
                    eq("candidate-a"), eq(-1), eq(200L));
        }
    }
}
