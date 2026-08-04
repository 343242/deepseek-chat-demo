package com.smart.rag.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smart.rag.agent.event.AgentSessionEvent;

import java.time.Instant;

/**
 * agent_session_event VO（管理员只读）。
 * <p>
 * data 为原始 JSONB 字符串（6 种 payload 形态），均已天然脱敏
 * （INTERMEDIATE_ANSWER 用 answerHash 而非正文；TOOL_CALLED 只存 resultDocCount 不存文档），
 * 无需额外处理，前端自行解析。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentEventVO(
    Long id,
    String sessionId,
    Long userId,
    String eventType,
    int priority,
    String data,
    String toolName,
    Boolean success,
    Long durationMs,
    Instant createdAt
) {

    public static AgentEventVO of(AgentSessionEvent e) {
        return new AgentEventVO(
            e.getId(), e.getSessionId(), e.getUserId(),
            e.getEventType() != null ? e.getEventType().name() : null,
            e.getPriority() != null ? e.getPriority().getValue() : 0,
            e.getData(), e.getToolName(), e.getSuccess(),
            e.getDurationMs(), e.getCreatedAt()
        );
    }
}
