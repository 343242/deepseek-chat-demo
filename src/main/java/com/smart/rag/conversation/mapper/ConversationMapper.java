package com.smart.rag.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.conversation.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/**
 * 会话 Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 递增消息计数 + 更新最后消息时间（原子操作）
     */
    int incrementMessageCount(@Param("conversationId") String conversationId,
                              @Param("delta") int delta,
                              @Param("lastMessageAt") OffsetDateTime lastMessageAt);

    /**
     * 条件更新标题：仅当 message_count = 0 且 title_source = SYSTEM 时更新（CAS 防并发）
     */
    int updateTitleIfFirst(@Param("id") Long id,
                           @Param("title") String title,
                           @Param("titleSource") String titleSource);

    /**
     * 更新会话标题（用户编辑）
     */
    int updateTitle(@Param("id") Long id,
                    @Param("title") String title,
                    @Param("titleSource") String titleSource);

    /**
     * 更新会话状态
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 更新置顶状态
     */
    int updatePinned(@Param("id") Long id, @Param("pinned") boolean pinned);
}
