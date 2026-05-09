package com.demo.chat.chat.mapper;

import com.demo.chat.chat.dto.ConversationMessage;
import com.demo.chat.chat.dto.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对话管理 Mapper
 * <p>
 * 封装 spring_ai_chat_memory 表的查询逻辑，
 * 消除 ConversationService 中的 JdbcTemplate 直接 SQL 拼接。
 */
@Mapper
public interface ConversationMapper {

    @Select("SELECT conversation_id, COUNT(*) AS msg_count, " +
            "MIN(created_at) AS first_msg, MAX(created_at) AS last_msg " +
            "FROM spring_ai_chat_memory " +
            "WHERE conversation_id LIKE #{prefix} " +
            "GROUP BY conversation_id " +
            "ORDER BY last_msg DESC LIMIT #{limit} OFFSET #{offset}")
    List<ConversationSummary> selectConversationsByPrefix(@Param("prefix") String prefix,
                                                           @Param("limit") int limit,
                                                           @Param("offset") int offset);

    @Select("SELECT role, content, created_at FROM spring_ai_chat_memory " +
            "WHERE conversation_id = #{conversationId} ORDER BY created_at ASC")
    List<ConversationMessage> selectMessagesByConversationId(@Param("conversationId") String conversationId);
}
