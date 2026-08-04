package com.smart.rag.conversation.service.impl;

import com.smart.rag.conversation.dto.MessageCursorPage;
import com.smart.rag.conversation.entity.Message;
import com.smart.rag.conversation.mapper.MessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息游标分页单元测试（{@link ConversationMessageServiceImpl#buildMessageTreePaged}）。
 * <p>
 * 数据模型：5 轮对话，根消息 id=1,3,5,7,9（USER），子消息 id=2,4,6,8,10（ASSISTANT，
 * parentId 指向同轮 USER）。id 自增 = 时间递增。
 * <p>
 * 覆盖契约：首屏 / 翻页 / 最后一页 / 空会话 / 刚好 limit 边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("消息游标分页（buildMessageTreePaged）")
class ConversationMessageServiceImplPagedTest {

    private static final String CONV = "conv";

    @Mock
    private MessageMapper messageMapper;

    @InjectMocks
    private ConversationMessageServiceImpl messageService;

    private Message userMsg(long id) {
        Message m = Message.userMessage(CONV, null, "u" + id);
        m.setId(id);
        return m;
    }

    private Message assistantMsg(long id, long parentId) {
        Message m = Message.assistantMessage(CONV, parentId, "a" + id, "model", 10, 100L);
        m.setId(id);
        return m;
    }

    @Nested
    @DisplayName("首屏 before=null")
    class FirstPage {

        @Test
        @DisplayName("取最近 2 轮，hasMore=true，nextCursor=本页最早根 id，items 时间升序")
        void returnsLatestRoundsWithCursor() {
            // limit+1=3 条根消息（id DESC）
            when(messageMapper.selectRootsPage(eq(CONV), isNull(), eq(3)))
                    .thenReturn(List.of(userMsg(9), userMsg(7), userMsg(5)));
            // reverse 后 rootIds=[7,9]
            when(messageMapper.selectChildrenOfRoots(eq(CONV), eq(List.of(7L, 9L))))
                    .thenReturn(List.of(assistantMsg(8, 7), assistantMsg(10, 9)));

            MessageCursorPage page = messageService.buildMessageTreePaged(CONV, null, 2);

            assertThat(page.items()).hasSize(2);
            // 时间升序：id=7 在前，带子消息 id=8
            assertThat(page.items().get(0).id()).isEqualTo(7L);
            assertThat(page.items().get(0).children()).hasSize(1);
            assertThat(page.items().get(0).children().get(0).id()).isEqualTo(8L);
            assertThat(page.items().get(1).id()).isEqualTo(9L);
            assertThat(page.items().get(1).children().get(0).id()).isEqualTo(10L);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.nextCursor()).isEqualTo(7L);
        }
    }

    @Test
    @DisplayName("翻页 before=7 取更早 2 轮")
    void nextPageWithCursor() {
        when(messageMapper.selectRootsPage(eq(CONV), eq(7L), eq(3)))
                .thenReturn(List.of(userMsg(5), userMsg(3), userMsg(1)));
        when(messageMapper.selectChildrenOfRoots(eq(CONV), eq(List.of(3L, 5L))))
                .thenReturn(List.of(assistantMsg(4, 3), assistantMsg(6, 5)));

        MessageCursorPage page = messageService.buildMessageTreePaged(CONV, 7L, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo(3L);
        assertThat(page.items().get(1).id()).isEqualTo(5L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(3L);
    }

    @Test
    @DisplayName("最后一页 before=3 仅剩 1 轮，hasMore=false，nextCursor=null")
    void lastPageNoMore() {
        when(messageMapper.selectRootsPage(eq(CONV), eq(3L), eq(3)))
                .thenReturn(List.of(userMsg(1)));
        when(messageMapper.selectChildrenOfRoots(eq(CONV), eq(List.of(1L))))
                .thenReturn(List.of(assistantMsg(2, 1)));

        MessageCursorPage page = messageService.buildMessageTreePaged(CONV, 3L, 2);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo(1L);
        assertThat(page.items().get(0).children().get(0).id()).isEqualTo(2L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("空会话返回空结果，不查子消息")
    void emptyConversation() {
        when(messageMapper.selectRootsPage(eq(CONV), isNull(), eq(3)))
                .thenReturn(Collections.emptyList());

        MessageCursorPage page = messageService.buildMessageTreePaged(CONV, null, 2);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(messageMapper, never()).selectChildrenOfRoots(eq(CONV), anyList());
    }

    @Test
    @DisplayName("总数刚好等于 limit，hasMore=false")
    void exactLimitNoMore() {
        when(messageMapper.selectRootsPage(eq(CONV), isNull(), eq(3)))
                .thenReturn(List.of(userMsg(9), userMsg(7)));
        when(messageMapper.selectChildrenOfRoots(eq(CONV), eq(List.of(7L, 9L))))
                .thenReturn(List.of(assistantMsg(8, 7), assistantMsg(10, 9)));

        MessageCursorPage page = messageService.buildMessageTreePaged(CONV, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
