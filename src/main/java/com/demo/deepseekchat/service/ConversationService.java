package com.demo.deepseekchat.service;

import com.demo.deepseekchat.model.dto.ConversationMessage;
import com.demo.deepseekchat.model.dto.ConversationSummary;
import com.demo.deepseekchat.security.util.SecurityUtils;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.chat-memory.table-name:spring_ai_chat_memory}")
    private String chatMemoryTableName;

    public ConversationService(ChatMemoryRepository chatMemoryRepository, JdbcTemplate jdbcTemplate) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取当前用户的对话摘要列表（按最后消息时间倒序，分页）
     */
    public List<ConversationSummary> listConversations(int page, int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        String prefix = "u_" + userId + "_%";
        int offset = (page - 1) * size;

        return jdbcTemplate.query(
                "SELECT conversation_id, COUNT(*) AS msg_count, " +
                "MIN(created_at) AS first_msg, MAX(created_at) AS last_msg " +
                "FROM " + chatMemoryTableName + " " +
                "WHERE conversation_id LIKE ? " +
                "GROUP BY conversation_id " +
                "ORDER BY last_msg DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    String rawConversationId = stripUserPrefix(rs.getString("conversation_id"));
                    return new ConversationSummary(
                            rawConversationId,
                            rs.getLong("msg_count"),
                            rs.getTimestamp("first_msg").toLocalDateTime(),
                            rs.getTimestamp("last_msg").toLocalDateTime()
                    );
                },
                prefix, size, offset
        );
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
        String isolatedId = buildIsolatedConversationId(userId, rawConversationId);

        return jdbcTemplate.query(
                "SELECT role, content, created_at FROM " + chatMemoryTableName + " " +
                "WHERE conversation_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> new ConversationMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ),
                isolatedId
        );
    }

    /**
     * 清空指定对话（当前用户）
     */
    public void clearConversation(String rawConversationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedId = buildIsolatedConversationId(userId, rawConversationId);
        chatMemoryRepository.deleteByConversationId(isolatedId);
    }

    /**
     * 构建用户隔离的 conversationId
     */
    private String buildIsolatedConversationId(Long userId, String rawConversationId) {
        return "u_" + userId + "_" + rawConversationId;
    }

    /**
     * 从存储的 conversationId 中剥离用户前缀
     * "u_123_default" → "default"
     */
    private String stripUserPrefix(String isolatedConversationId) {
        if (isolatedConversationId == null) return null;
        // 匹配 u_{userId}_{rest} 格式
        int firstUnderscore = isolatedConversationId.indexOf('_');
        if (firstUnderscore < 0) return isolatedConversationId;
        int secondUnderscore = isolatedConversationId.indexOf('_', firstUnderscore + 1);
        if (secondUnderscore < 0) return isolatedConversationId;
        return isolatedConversationId.substring(secondUnderscore + 1);
    }
}
