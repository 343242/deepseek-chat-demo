package com.smart.rag.chat.service;

import com.smart.rag.mode.StrategyExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.SignalType;

import static org.mockito.Mockito.*;

/**
 * StreamCompletionHelper 回归测试（design chat-stream-cancel.md §5.2）。
 * <p>
 * 验证 CANCEL/ON_ERROR 不落库（取消即作废），ON_COMPLETE 仍正常落库。
 * usage 由策略层 doFinally 独立记录，不经本 helper——此处不验证。
 */
@DisplayName("StreamCompletionHelper 落库语义")
class StreamCompletionHelperTest {

    private static StrategyExecutionContext mockCtx() {
        StrategyExecutionContext ctx = mock(StrategyExecutionContext.class);
        when(ctx.conversationId()).thenReturn("u_1_conv-1");
        when(ctx.candidateId()).thenReturn("model-a");
        when(ctx.elapsed()).thenReturn(100L);
        com.smart.rag.mode.ChatRequest req = mock(com.smart.rag.mode.ChatRequest.class);
        when(req.message()).thenReturn("hello");
        when(ctx.request()).thenReturn(req);
        return ctx;
    }

    @Test
    @DisplayName("ON_COMPLETE 正常落库（回归）")
    void onCompletePersists() {
        StrategyExecutionContext ctx = mockCtx();
        ChatMessagePublisher publisher = mock(ChatMessagePublisher.class);
        ChatConversationHelper helper = mock(ChatConversationHelper.class);

        StreamCompletionHelper.onComplete(ctx, "full reply", SignalType.ON_COMPLETE, publisher, helper);

        verify(publisher).publishMessageSave(eq("u_1_conv-1"), eq("hello"), eq("full reply"),
                eq("model-a"), isNull(), eq(100L));
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("CANCEL 不落库（取消即作废，design §5.2）")
    void cancelDoesNotPersist() {
        StrategyExecutionContext ctx = mockCtx();
        ChatMessagePublisher publisher = mock(ChatMessagePublisher.class);
        ChatConversationHelper helper = mock(ChatConversationHelper.class);

        StreamCompletionHelper.onComplete(ctx, "partial reply", SignalType.CANCEL, publisher, helper);

        verifyNoInteractions(publisher);
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("ON_ERROR 不落库（与 CANCEL 统一，design §5.2）")
    void onErrorDoesNotPersist() {
        StrategyExecutionContext ctx = mockCtx();
        ChatMessagePublisher publisher = mock(ChatMessagePublisher.class);
        ChatConversationHelper helper = mock(ChatConversationHelper.class);

        StreamCompletionHelper.onComplete(ctx, "partial", SignalType.ON_ERROR, publisher, helper);

        verifyNoInteractions(publisher);
        verifyNoInteractions(helper);
    }
}
