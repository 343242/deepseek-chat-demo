package com.demo.chat.chat.service;

import com.demo.chat.chat.dto.ConversationMessage;
import com.demo.chat.chat.dto.ConversationSummary;

import java.util.List;

/**
 * 对话管理服务接口（用户隔离版）
 */
public interface ConversationService {

    List<ConversationSummary> listConversations(int page, int size);

    List<ConversationSummary> listConversations();

    List<ConversationMessage> getConversationMessages(String rawConversationId);

    void clearConversation(String rawConversationId);
}
