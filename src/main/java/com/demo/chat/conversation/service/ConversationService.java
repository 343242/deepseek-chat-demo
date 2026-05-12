package com.demo.chat.conversation.service;

import com.demo.chat.conversation.dto.ConversationCreateRequest;
import com.demo.chat.conversation.dto.ConversationDetail;
import com.demo.chat.conversation.dto.ConversationSummary;
import com.demo.chat.conversation.dto.ConversationUpdateRequest;
import com.demo.chat.conversation.dto.MessageVO;

import java.util.List;

/**
 * 会话管理服务（面向前端 API）
 * <p>
 * 提供会话的 CRUD 操作，自动绑定当前登录用户。
 */
public interface ConversationService {

    /**
     * 创建新会话
     *
     * @param userId  用户 ID
     * @param request 创建请求
     * @return 会话摘要
     */
    ConversationSummary create(Long userId, ConversationCreateRequest request);

    /**
     * 获取或自动创建会话
     * <p>
     * 如果 conversationId 对应的会话不存在，自动创建一条。
     * 用于 ChatService 集成：每次聊天时确保会话记录存在。
     *
     * @param userId         用户 ID
     * @param conversationId 隔离后的 conversation_id
     * @param modelId        模型 ID（自动创建时使用）
     * @return 会话摘要
     */
    ConversationSummary getOrCreate(Long userId, String conversationId, String modelId);

    /**
     * 查询用户会话列表
     *
     * @param userId 用户 ID
     * @param status 状态过滤（可选，null 表示不过滤）
     * @param page   页码（从 1 开始）
     * @param size   每页大小
     * @return 会话摘要列表（置顶优先，然后按 last_message_at 降序）
     */
    List<ConversationSummary> list(Long userId, String status, int page, int size);

    /**
     * 获取会话详情（含消息树）
     *
     * @param userId         用户 ID
     * @param conversationId 隔离后的 conversation_id
     * @return 会话详情
     */
    ConversationDetail getDetail(Long userId, String conversationId);

    /**
     * 更新会话（标题/置顶/归档）
     *
     * @param userId         用户 ID
     * @param conversationId 隔离后的 conversation_id
     * @param request        更新请求
     */
    void update(Long userId, String conversationId, ConversationUpdateRequest request);

    /**
     * 删除会话（软删除 + 清空 Spring AI memory）
     *
     * @param userId         用户 ID
     * @param conversationId 隔离后的 conversation_id
     */
    void delete(Long userId, String conversationId);

    /**
     * 获取会话的消息列表
     *
     * @param userId         用户 ID
     * @param conversationId 隔离后的 conversation_id
     * @return 消息树列表
     */
    List<MessageVO> listMessages(Long userId, String conversationId);

    /**
     * 通知会话有新消息（由 ChatService 调用）
     * <p>
     * 更新 message_count 和 last_message_at，首次消息时自动设置标题。
     *
     * @param conversationId 隔离后的 conversation_id
     * @param userContent    用户消息内容（用于自动生成标题）
     * @param delta          消息增量（通常为 2：一条 USER + 一条 ASSISTANT）
     */
    void onNewMessages(String conversationId, String userContent, int delta);
}
