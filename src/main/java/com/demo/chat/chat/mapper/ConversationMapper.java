package com.demo.chat.chat.mapper;

import com.demo.chat.chat.dto.ConversationMessage;
import com.demo.chat.chat.dto.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话管理 Mapper
 * <p>
 * 封装 spring_ai_chat_memory 表的查询逻辑，
 * 消除 ConversationService 中的 JdbcTemplate 直接 SQL 拼接。
 */
@Mapper
public interface ConversationMapper {

    List<ConversationSummary> selectConversationsByPrefix(@Param("prefix") String prefix,
                                                           @Param("limit") int limit,
                                                           @Param("offset") int offset);

    List<ConversationMessage> selectMessagesByConversationId(@Param("conversationId") String conversationId);
}
