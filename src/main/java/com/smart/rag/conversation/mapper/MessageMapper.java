package com.smart.rag.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.conversation.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息 Mapper
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 查询会话下所有消息（按创建时间升序）
     */
    List<Message> selectAllByConversationId(@Param("conversationId") String conversationId);

    /**
     * 查询会话下所有根消息（无 parent_id 的消息）
     */
    List<Message> selectRootMessages(@Param("conversationId") String conversationId);

    /**
     * 查询某个父消息的所有子消息（分支），带 conversation_id 约束
     */
    List<Message> selectChildren(@Param("parentId") Long parentId,
                                  @Param("conversationId") String conversationId);

    /**
     * 查询会话下最新一条 ASSISTANT 消息
     */
    Message selectLatestAssistant(@Param("conversationId") String conversationId);

    /**
     * 统计会话下的消息总数
     */
    int countByConversationId(@Param("conversationId") String conversationId);
}
