package com.smart.rag.rag.agent.tool;

import com.smart.rag.rag.agent.dto.ToolResult;
import com.smart.rag.rag.agent.event.AgentEventStore;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史事件回溯 Tool -- 查找 Agent 历史事件（P2 优化，会话连续性）
 * <p>
 * 委托 {@link AgentEventStore#searchEvents} 从 PostgreSQL 事件表按需检索历史事件。
 * 利用 PostgreSQL JSONB 全文能力搜索，含 userId 多租户隔离。
 */
@Component
public class AgentEventLookupTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(AgentEventLookupTool.class);

    /** 最大返回事件条数 */
    private static final int MAX_RESULTS = 5;

    private final AgentEventStore eventStore;

    public AgentEventLookupTool(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * 查找历史事件
     *
     * @param queryText 查询文本
     * @param sessionId 会话 ID（可空，空时使用 workspace 中的用户 ID 检索最近事件）
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String queryText, String sessionId, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("agentEventLookup",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson();
            }

            Long userId = workspace.getUserId();
            String sid = (sessionId != null && !sessionId.isBlank())
                ? sessionId
                : "unknown";

            log.debug("Agent event lookup: query='{}', session={}, userId={}",
                queryText, sid, userId);

            List<?> events = eventStore.searchEvents(sid, userId, queryText, MAX_RESULTS);

            long duration = System.currentTimeMillis() - start;
            String summary = events.isEmpty()
                ? "未找到匹配的历史事件"
                : "找到 %d 条匹配的历史事件".formatted(events.size());

            return ToolResult.success("agentEventLookup",
                summary, null, duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Agent event lookup error", e);
            return ToolResult.failure("agentEventLookup",
                "历史事件查找发生错误：" + e.getMessage(),
                "DB_ERROR", duration).toJson();
        }
    }
}
