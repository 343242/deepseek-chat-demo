package com.smart.rag.chat.context;
import com.smart.rag.mode.SessionContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultSessionContextResolver 测试
 */
@ExtendWith(MockitoExtension.class)
class DefaultSessionContextResolverTest {

    private final DefaultSessionContextResolver resolver = new DefaultSessionContextResolver();

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("消息数为 0 → 首次对话")
        void zeroMessages_firstTime() {
            SessionContext ctx = resolver.resolve("conv-1", 0);
            assertEquals("首次对话", ctx.stage());
            assertEquals(0, ctx.messageCount());
        }

        @Test
        @DisplayName("消息数 1-4 → 对话初期")
        void fewMessages_earlyStage() {
            assertEquals("对话初期", resolver.resolve("conv-1", 1).stage());
            assertEquals("对话初期", resolver.resolve("conv-1", 4).stage());
        }

        @Test
        @DisplayName("消息数 5-14 → 深入交流")
        void mediumMessages_deepTalk() {
            assertEquals("深入交流", resolver.resolve("conv-1", 5).stage());
            assertEquals("深入交流", resolver.resolve("conv-1", 14).stage());
        }

        @Test
        @DisplayName("消息数 ≥ 15 → 长对话")
        void manyMessages_longConversation() {
            assertEquals("长对话", resolver.resolve("conv-1", 15).stage());
            assertEquals("长对话", resolver.resolve("conv-1", 100).stage());
        }

        @Test
        @DisplayName("conversationId 正确传递")
        void conversationIdPassed() {
            SessionContext ctx = resolver.resolve("my-conv-id", 3);
            assertEquals("my-conv-id", ctx.conversationId());
        }
    }
}
