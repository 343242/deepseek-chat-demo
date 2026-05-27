package com.smart.rag.rag.agent.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Agent 会话事件 Mapper
 * <p>
 * MyBatis-Plus BaseMapper 提供标准 CRUD，自定义方法用于事件搜索和优先级查询。
 */
@Mapper
public interface AgentEventMapper extends BaseMapper<AgentSessionEvent> {

    /**
     * 按会话 ID 查询事件（按优先级升序、创建时间升序排列）
     * <p>
     * 优先级 1 (Critical) 排在最前，保证恢复时优先读取关键事件。
     * <p>
     * 限制最多返回 {@code limit} 条事件，避免长会话一次性加载过多数据。
     */
    @Select("SELECT * FROM agent_session_event " +
            "WHERE session_id = #{sessionId} AND user_id = #{userId} " +
            "ORDER BY priority ASC, created_at ASC " +
            "LIMIT #{limit}")
    List<AgentSessionEvent> selectBySessionIdOrderByPriorityLimited(
        @Param("sessionId") String sessionId,
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 按会话 ID 查询事件（无条数限制，仅用于向后兼容）
     * <p>
     * 优先级 1 (Critical) 排在最前，保证恢复时优先读取关键事件。
     */
    @Select("SELECT * FROM agent_session_event " +
            "WHERE session_id = #{sessionId} AND user_id = #{userId} " +
            "ORDER BY priority ASC, created_at ASC")
    List<AgentSessionEvent> selectBySessionIdOrderByPriority(
        @Param("sessionId") String sessionId,
        @Param("userId") Long userId
    );

    /**
     * 搜索历史事件 -- JSONB 全文搜索 + userId 多租户隔离
     * <p>
     * 搜索策略：
     * <ol>
     *   <li>PostgreSQL JSONB text 模式搜索（ILIKE）</li>
     *   <li>按创建时间倒序，最多返回 limit 条</li>
     *   <li>sessionId 为 null 时不按 session 过滤，仅按 userId + queryText 检索</li>
     * </ol>
     */
    @Select("<script>" +
            "SELECT * FROM agent_session_event " +
            "WHERE user_id = #{userId} " +
            "<if test='sessionId != null'>AND session_id = #{sessionId} </if>" +
            "AND (data::text ILIKE '%' || #{query} || '%' " +
            "     OR event_type ILIKE '%' || #{query} || '%') " +
            "ORDER BY created_at DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<AgentSessionEvent> searchBySessionAndUserAndQuery(
        @Param("sessionId") String sessionId,
        @Param("userId") Long userId,
        @Param("query") String query,
        @Param("limit") int limit
    );

    /**
     * 删除早于指定时间的事件（TTL 清理）
     *
     * @param cutoff 截止时间，早于此时间的事件将被删除
     * @return 删除的行数
     */
    @Delete("DELETE FROM agent_session_event WHERE created_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
