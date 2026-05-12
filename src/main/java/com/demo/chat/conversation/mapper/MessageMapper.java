package com.demo.chat.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.conversation.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 消息 Mapper
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 查询某个会话下的所有根消息（无 parent_id 的消息）
     */
    @Select("SELECT * FROM message WHERE conversation_id = #{conversationId} " +
            "AND parent_id IS NULL ORDER BY created_at ASC")
    List<Message> selectRootMessages(@Param("conversationId") String conversationId);

    /**
     * 查询某个父消息的所有子消息（分支）
     */
    @Select("SELECT * FROM message WHERE parent_id = #{parentId} ORDER BY created_at ASC")
    List<Message> selectChildren(@Param("parentId") Long parentId);

    /**
     * 查询会话下最新一条 ASSISTANT 消息
     */
    @Select("SELECT * FROM message WHERE conversation_id = #{conversationId} " +
            "AND role = 'ASSISTANT' ORDER BY created_at DESC LIMIT 1")
    Message selectLatestAssistant(@Param("conversationId") String conversationId);

    /**
     * 统计会话下的消息总数
     */
    @Select("SELECT COUNT(*) FROM message WHERE conversation_id = #{conversationId}")
    int countByConversationId(@Param("conversationId") String conversationId);
}
