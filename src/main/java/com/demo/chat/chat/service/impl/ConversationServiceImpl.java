package com.demo.chat.chat.service.impl;

import com.demo.chat.chat.dto.ConversationMessage;
import com.demo.chat.chat.dto.ConversationSummary;
import com.demo.chat.chat.mapper.ConversationMapper;
import com.demo.chat.chat.service.ConversationService;
import com.demo.chat.chat.util.ConversationIdUtil;
import com.demo.chat.security.util.SecurityUtils;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话管理服务（用户隔离版）
 * <p>
 * 所有查询和操作强制绑定当前用户 ID，确保对话记录完全隔离。
 * conversationId 在存储层使用 "u_{userId}_{rawId}" 格式，
 * 对外返回时剥离 userId 前缀，保持 API 透明。
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ConversationMapper conversationMapper;

    public ConversationServiceImpl(ChatMemoryRepository chatMemoryRepository,
                                   ConversationMapper conversationMapper) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.conversationMapper = conversationMapper;
    }

    @Override
    public List<ConversationSummary> listConversations(int page, int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        int offset = (page - 1) * size;
        return conversationMapper.selectConversationsByPrefix(prefix, size, offset);
    }

    @Override
    public List<ConversationSummary> listConversations() {
        return listConversations(1, 100);
    }

    @Override
    public List<ConversationMessage> getConversationMessages(String rawConversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);
        return conversationMapper.selectMessagesByConversationId(isolatedId);
    }

    @Override
    public void clearConversation(String rawConversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);
        chatMemoryRepository.deleteByConversationId(isolatedId);
    }
}
