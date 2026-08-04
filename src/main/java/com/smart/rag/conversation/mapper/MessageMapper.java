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

    /**
     * 游标分页查询根消息（按 id 倒序取最近 / 游标之前的 N 条）。
     * <p>
     * 调用方通常传 {@code limit + 1} 条数用于判断 hasMore。
     *
     * @param conversationId 会话 ID
     * @param before         游标（根消息 id），{@code null} 表示从最新开始
     * @param limit          取的条数
     */
    List<Message> selectRootsPage(@Param("conversationId") String conversationId,
                                  @Param("before") Long before,
                                  @Param("limit") int limit);

    /**
     * 批量查询多个根消息的子消息（{@code parent_id IN (...)})，用于组装消息树。
     *
     * @param conversationId 会话 ID（作用域约束，防跨会话）
     * @param rootIds        根消息 id 集合（非空）
     */
    List<Message> selectChildrenOfRoots(@Param("conversationId") String conversationId,
                                        @Param("rootIds") List<Long> rootIds);
}
