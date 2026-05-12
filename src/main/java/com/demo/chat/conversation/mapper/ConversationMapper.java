package com.demo.chat.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.conversation.entity.Conversation;
import com.demo.chat.conversation.enums.ConversationStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话 Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 递增消息计数 + 更新最后消息时间
     */
    @Update("UPDATE conversation SET message_count = message_count + #{delta}, " +
            "last_message_at = #{lastMessageAt}, updated_at = NOW() " +
            "WHERE conversation_id = #{conversationId} AND status != 'DELETED'")
    int incrementMessageCount(@Param("conversationId") String conversationId,
                              @Param("delta") int delta,
                              @Param("lastMessageAt") LocalDateTime lastMessageAt);

    /**
     * 更新会话标题
     */
    @Update("UPDATE conversation SET title = #{title}, title_source = #{titleSource}, " +
            "updated_at = NOW() WHERE id = #{id}")
    int updateTitle(@Param("id") Long id,
                    @Param("title") String title,
                    @Param("titleSource") String titleSource);

    /**
     * 更新会话状态
     */
    @Update("UPDATE conversation SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 更新置顶状态
     */
    @Update("UPDATE conversation SET pinned = #{pinned}, updated_at = NOW() WHERE id = #{id}")
    int updatePinned(@Param("id") Long id, @Param("pinned") boolean pinned);
}
