package com.smart.rag.conversation.service;

import com.smart.rag.common.response.PagedResult;
import com.smart.rag.conversation.dto.ConversationCreateRequest;
import com.smart.rag.conversation.dto.ConversationDetail;
import com.smart.rag.conversation.dto.ConversationSummary;
import com.smart.rag.conversation.dto.ConversationUpdateRequest;
import com.smart.rag.conversation.dto.MessageVO;

import java.util.List;

/**
 * 会话管理服务（面向前端 API）
 * <p>
 * 提供会话的 CRUD 操作，自动绑定当前登录用户。
 */
public interface ConversationService {

    ConversationSummary create(Long userId, ConversationCreateRequest request);

    /**
     * 获取或自动创建会话
     * <p>
     * 如果 conversationId 对应的会话不存在，自动创建一条。
     * 用于 ChatService 集成：每次聊天时确保会话记录存在。
     */
    ConversationSummary getOrCreate(Long userId, String conversationId, String modelId);

    /**
     * 查询用户会话列表（分页，支持状态过滤）
     *
     * @param page   页码（从 1 开始）
     * @param size   每页大小
     * @param status 状态过滤（可选：ACTIVE / ARCHIVED）
     */
    PagedResult<ConversationSummary> list(Long userId, String status, int page, int size);

    /** 获取会话详情（含消息树） */
    ConversationDetail getDetail(Long userId, String conversationId);

    /** 更新会话（标题/置顶/归档） */
    void update(Long userId, String conversationId, ConversationUpdateRequest request);

    /** 删除会话（软删除 + 清空 Spring AI memory + 清理 message） */
    void delete(Long userId, String conversationId);

    /** 获取会话的消息列表（树形） */
    List<MessageVO> listMessages(Long userId, String conversationId);

    /**
     * 通知会话有新消息（由 ChatService 调用）
     * <p>
     * 原子递增 message_count，首次消息时 CAS 设置标题。
     */
    void onNewMessages(String conversationId, String userContent, int delta);
}
