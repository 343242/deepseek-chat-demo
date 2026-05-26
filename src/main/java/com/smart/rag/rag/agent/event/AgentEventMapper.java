package com.smart.rag.rag.agent.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * </ol>
     */
    @Select("SELECT * FROM agent_session_event " +
            "WHERE session_id = #{sessionId} " +
            "AND user_id = #{userId} " +
            "AND (data::text ILIKE '%' || #{query} || '%' " +
            "     OR event_type ILIKE '%' || #{query} || '%') " +
            "ORDER BY created_at DESC " +
            "LIMIT #{limit}")
    List<AgentSessionEvent> searchBySessionAndUserAndQuery(
        @Param("sessionId") String sessionId,
        @Param("userId") Long userId,
        @Param("query") String query,
        @Param("limit") int limit
    );
}
