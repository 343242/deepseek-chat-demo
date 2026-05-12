package com.demo.chat.conversation.service.impl;

import com.demo.chat.conversation.entity.Message;
import com.demo.chat.conversation.mapper.MessageMapper;
import com.demo.chat.conversation.service.ConversationQueryService;
import org.springframework.stereotype.Service;

/**
 * 会话查询服务实现（面向内部模块）
 */
@Service
public class ConversationQueryServiceImpl implements ConversationQueryService {

    private final MessageMapper messageMapper;

    public ConversationQueryServiceImpl(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public int getMessageCount(String conversationId) {
        return messageMapper.countByConversationId(conversationId);
    }

    @Override
    public Message saveMessage(Message message) {
        messageMapper.insert(message);
        return message;
    }
}
