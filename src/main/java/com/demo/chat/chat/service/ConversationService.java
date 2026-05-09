package com.demo.chat.chat.service;

import com.demo.chat.chat.dto.ConversationMessage;
import com.demo.chat.chat.dto.ConversationSummary;
import com.demo.chat.chat.mapper.ConversationMapper;
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
public class ConversationService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ConversationMapper conversationMapper;

    public ConversationService(ChatMemoryRepository chatMemoryRepository,
                               ConversationMapper conversationMapper) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 获取当前用户的对话摘要列表（按最后消息时间倒序，分页）
     */
    public List<ConversationSummary> listConversations(int page, int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        String prefix = ConversationIdUtil.buildLikePrefix(userId);
        int offset = (page - 1) * size;
        return conversationMapper.selectConversationsByPrefix(prefix, size, offset);
    }

    /**
     * 获取当前用户的对话摘要列表（不分页，兼容旧调用）
     */
    public List<ConversationSummary> listConversations() {
        return listConversations(1, 100);
    }

    /**
     * 获取指定对话的消息列表（当前用户，按时间正序）
     */
    public List<ConversationMessage> getConversationMessages(String rawConversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);
        return conversationMapper.selectMessagesByConversationId(isolatedId);
    }

    /**
     * 清空指定对话（当前用户）
     */
    public void clearConversation(String rawConversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);
        chatMemoryRepository.deleteByConversationId(isolatedId);
    }
}
