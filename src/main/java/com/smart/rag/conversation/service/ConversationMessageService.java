package com.smart.rag.conversation.service;

import com.smart.rag.conversation.entity.Message;
import com.smart.rag.conversation.dto.MessageVO;

import java.util.List;

/**
 * 会话消息服务（面向内部模块，如 chat、CAG）
 * <p>
 * 提供 chat 模块需要的消息写入和查询能力。
 */
public interface ConversationMessageService {

    /**
     * 写入一条消息记录
     *
     * @param message 消息实体
     * @return 写入后的消息（含自增 ID）
     */
    Message saveMessage(Message message);

    /**
     * 查询会话的消息数量
     *
     * @param conversationId 隔离后的 conversation_id
     * @return 消息数量
     */
    int getMessageCount(String conversationId);

    /**
     * 构建会话的消息树（根消息 + 一层子消息）
     *
     * @param conversationId 隔离后的 conversation_id
     * @return 消息树列表
     */
    List<MessageVO> buildMessageTree(String conversationId);

    /**
     * 软删除会话下的所有消息
     *
     * @param conversationId 隔离后的 conversation_id
     */
    void deleteByConversationId(String conversationId);
}
