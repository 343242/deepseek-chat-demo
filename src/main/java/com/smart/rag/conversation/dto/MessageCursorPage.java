package com.smart.rag.conversation.dto;

import java.util.List;

/**
 * 消息游标分页结果。
 * <p>
 * 用于会话消息的「最近 N 轮 + 向上翻历史」分页。每轮对话由一条 USER 根消息
 * 及其 ASSISTANT 子消息组成，因此游标粒度为根消息 id。
 * <p>
 * 分页方向：从最新向最早翻页（时间倒序加载历史）。返回的 {@link #items} 仍按
 * 时间升序排列，与原 {@code listMessages} 保持一致，便于前端正序渲染。
 *
 * @param items      当前页消息树（按时间升序）
 * @param nextCursor 下一页起点 —— 本页最早的根消息 id；{@code null} 表示已到最早，无更多历史
 * @param hasMore    是否还有更早的历史消息可加载
 */
public record MessageCursorPage(
        List<MessageVO> items,
        Long nextCursor,
        boolean hasMore
) {}
