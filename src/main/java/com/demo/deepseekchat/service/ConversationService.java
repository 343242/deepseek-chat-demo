package com.demo.deepseekchat.service;

import com.demo.deepseekchat.model.dto.ConversationMessage;
import com.demo.deepseekchat.model.dto.ConversationSummary;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话管理服务
 * <p>
 * 基于 ChatMemoryRepository + JdbcTemplate 实现对话历史查询、清空、导出。
 * ChatMemoryRepository 接口只提供 get/delete，聚合查询通过 JdbcTemplate 直查底层表。
 */
@Service
public class ConversationService {

    private final ChatMemoryRepository chatMemoryRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Spring AI JDBC ChatMemoryRepository 的底层表名，可通过配置覆盖
     */
    @Value("${app.chat-memory.table-name:spring_ai_chat_memory}")
    private String chatMemoryTableName;

    public ConversationService(ChatMemoryRepository chatMemoryRepository, JdbcTemplate jdbcTemplate) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取所有对话摘要列表（按最后消息时间倒序，分页）
     */
    public List<ConversationSummary> listConversations(int page, int size) {
        int offset = (page - 1) * size;
        return jdbcTemplate.query(
                "SELECT conversation_id, COUNT(*) AS msg_count, " +
                "MIN(created_at) AS first_msg, MAX(created_at) AS last_msg " +
                "FROM " + chatMemoryTableName + " " +
                "GROUP BY conversation_id " +
                "ORDER BY last_msg DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new ConversationSummary(
                        rs.getString("conversation_id"),
                        rs.getLong("msg_count"),
                        rs.getTimestamp("first_msg").toLocalDateTime(),
                        rs.getTimestamp("last_msg").toLocalDateTime()
                ),
                size, offset
        );
    }

    /**
     * 获取所有对话摘要列表（不分页，兼容旧调用）
     */
    public List<ConversationSummary> listConversations() {
        return listConversations(1, 100);
    }

    /**
     * 获取指定对话的消息列表（按时间正序）
     */
    public List<ConversationMessage> getConversationMessages(String conversationId) {
        return jdbcTemplate.query(
                "SELECT role, content, created_at FROM " + chatMemoryTableName + " " +
                "WHERE conversation_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> new ConversationMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ),
                conversationId
        );
    }

    /**
     * 清空指定对话的所有消息
     */
    public void clearConversation(String conversationId) {
        chatMemoryRepository.deleteByConversationId(conversationId);
    }
}
