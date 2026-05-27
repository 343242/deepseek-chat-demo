package com.smart.rag.rag.agent.tool;

import com.smart.rag.rag.agent.dto.ToolResult;
import com.smart.rag.rag.agent.event.AgentEventStore;
import com.smart.rag.rag.agent.event.AgentSessionEvent;
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
                : null;

            log.debug("Agent event lookup: queryLen={}, sessionId={}, userId={}", queryText.length(), sid, userId);

            List<AgentSessionEvent> events = eventStore.searchEvents(sid, userId, queryText, MAX_RESULTS);

            long duration = System.currentTimeMillis() - start;
            String summary = formatEventSummary(events);

            return ToolResult.success("agentEventLookup",
                summary, null, duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Agent event lookup error", e);
            return ToolResult.failure("agentEventLookup",
                ToolErrorMessages.eventLookupUnavailable(),
                "DB_ERROR", duration).toJson();
        }
    }

    /**
     * 将事件列表格式化为可读摘要文本，供 LLM 理解实际内容
     */
    private String formatEventSummary(List<AgentSessionEvent> events) {
        if (events.isEmpty()) {
            return "未找到匹配的历史事件";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(events.size()).append(" 条匹配事件:\n");
        for (AgentSessionEvent e : events) {
            String dataPreview = e.getData() != null && e.getData().length() > 200
                ? e.getData().substring(0, 200) + "..."
                : (e.getData() != null ? e.getData() : "");
            sb.append("- [").append(e.getEventType()).append("] ")
                .append(e.getCreatedAt() != null ? e.getCreatedAt().toString() : "N/A")
                .append(": ").append(dataPreview).append("\n");
        }
        return sb.toString().trim();
    }
}
