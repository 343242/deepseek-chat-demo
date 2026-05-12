package com.demo.chat.conversation.service;

import com.demo.chat.conversation.entity.Message;

/**
 * 会话查询服务（面向内部模块，如 chat、CAG）
 * <p>
 * 提供 chat 模块需要的查询能力，不暴露管理操作。
 */
public interface ConversationQueryService {

    /**
     * 查询会话的消息数量
     *
     * @param conversationId 隔离后的 conversation_id
     * @return 消息数量
     */
    int getMessageCount(String conversationId);

    /**
     * 写入一条消息记录
     *
     * @param message 消息实体
     * @return 写入后的消息（含自增 ID）
     */
    Message saveMessage(Message message);
}
