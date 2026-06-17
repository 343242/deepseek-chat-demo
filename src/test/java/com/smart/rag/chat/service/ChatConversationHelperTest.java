package com.smart.rag.chat.service;

import com.smart.rag.conversation.entity.Message;
import com.smart.rag.conversation.service.ConversationMessageService;
import com.smart.rag.conversation.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatConversationHelper 测试
 */
@ExtendWith(MockitoExtension.class)
class ChatConversationHelperTest {

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConversationMessageService conversationMessageService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ChatMemory chatMemory;

    private ChatConversationHelper createHelper() {
        return new ChatConversationHelper(
                conversationService, conversationMessageService, transactionTemplate, chatMemory);
    }

    @SuppressWarnings("unchecked")
    private void setupTransactionTemplate() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any(Consumer.class));
    }

    @Nested
    @DisplayName("getMessageCount")
    class GetMessageCount {

        @Test
        @DisplayName("正常返回消息数量")
        void returnsCount() {
            when(chatMemory.get("conv-1")).thenReturn(List.of(
                    new org.springframework.ai.chat.messages.UserMessage("hello"),
                    new AssistantMessage("hi")));

            ChatConversationHelper helper = createHelper();
            assertEquals(2, helper.getMessageCount("conv-1"));
        }

        @Test
        @DisplayName("ChatMemory 异常时返回 0")
        void returnsZeroOnException() {
            when(chatMemory.get("conv-1")).thenThrow(new RuntimeException("redis down"));

            ChatConversationHelper helper = createHelper();
            assertEquals(0, helper.getMessageCount("conv-1"));
        }
    }

    @Nested
    @DisplayName("ensureConversationExists")
    class EnsureConversationExists {

        @Test
        @DisplayName("正常创建会话")
        void createsConversation() {
            ChatConversationHelper helper = createHelper();
            helper.ensureConversationExists(1L, "conv-1", "gpt-4");

            verify(conversationService).getOrCreate(1L, "conv-1", "gpt-4");
        }

        @Test
        @DisplayName("并发 DuplicateKeyException 不抛出")
        void ignoresDuplicateKey() {
            doThrow(new DuplicateKeyException("duplicate"))
                    .when(conversationService).getOrCreate(anyLong(), anyString(), anyString());

            ChatConversationHelper helper = createHelper();
            helper.ensureConversationExists(1L, "conv-1", "gpt-4");
        }

        @Test
        @DisplayName("其他异常向外传播")
        void propagatesOtherExceptions() {
            doThrow(new RuntimeException("connection failed"))
                    .when(conversationService).getOrCreate(anyLong(), anyString(), anyString());

            ChatConversationHelper helper = createHelper();
            assertThrows(RuntimeException.class,
                    () -> helper.ensureConversationExists(1L, "conv-1", "gpt-4"));
        }
    }

    @Nested
    @DisplayName("saveMessagesAndNotify")
    class SaveMessagesAndNotify {

        @Test
        @DisplayName("正常保存 USER + ASSISTANT 消息并更新会话")
        void savesMessagesAndNotifies() {
            setupTransactionTemplate();
            ChatConversationHelper helper = createHelper();

            helper.saveMessagesAndNotify("conv-1", "hello", "hi there", "gpt-4", null, 100L);

            verify(conversationMessageService, times(2)).saveMessage(any(Message.class));
            verify(conversationService).onNewMessages("conv-1", "hello", 2);
        }

        @Test
        @DisplayName("事务失败不向外传播（静默处理）")
        void swallowsTransactionFailure() {
            doThrow(new RuntimeException("tx failed"))
                    .when(transactionTemplate).executeWithoutResult(any(Consumer.class));

            ChatConversationHelper helper = createHelper();
            helper.saveMessagesAndNotify("conv-1", "hello", "hi", "gpt-4", null, 100L);
        }
    }

    @Nested
    @DisplayName("savePartialResponse")
    class SavePartialResponse {

        @Test
        @DisplayName("空内容不保存")
        void skipsBlankContent() {
            ChatConversationHelper helper = createHelper();
            helper.savePartialResponse("conv-1", "");
            helper.savePartialResponse("conv-1", "   ");
            helper.savePartialResponse("conv-1", null);

            verifyNoInteractions(chatMemory);
        }

        @Test
        @DisplayName("历史为空时正常保存")
        void savesWhenHistoryEmpty() {
            when(chatMemory.get("conv-1")).thenReturn(List.of());

            ChatConversationHelper helper = createHelper();
            helper.savePartialResponse("conv-1", "partial content");

            verify(chatMemory).add(eq("conv-1"), any(AssistantMessage.class));
        }

        @Test
        @DisplayName("ChatMemory 异常不向外传播")
        void swallowsChatMemoryException() {
            when(chatMemory.get("conv-1")).thenThrow(new RuntimeException("redis down"));

            ChatConversationHelper helper = createHelper();
            helper.savePartialResponse("conv-1", "partial content");
        }
    }
}
