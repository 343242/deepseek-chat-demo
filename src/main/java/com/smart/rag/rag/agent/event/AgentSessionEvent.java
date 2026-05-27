package com.smart.rag.rag.agent.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Agent 会话事件实体
 * <p>
 * 对应表 agent_session_event，记录 Agent 每步事件的详细信息。
 * 供会话连续性恢复（P2 优化）和可观测性使用。
 * <p>
 * 事件类型及优先级：
 * <ul>
 *   <li>INTENT_CLASSIFIED (CRITICAL) -- 意图分类结果</li>
 *   <li>INTERMEDIATE_ANSWER (CRITICAL) -- 子问题中间答案</li>
 *   <li>SELF_REFLECTION (HIGH) -- 自省结果</li>
 *   <li>RETRIEVAL_STRATEGY (HIGH) -- 检索策略变更</li>
 *   <li>TOOL_CALLED (NORMAL) -- Tool 调用记录</li>
 *   <li>GUARDRAIL_TRIGGERED (CRITICAL) -- 护栏触发</li>
 * </ul>
 */
@TableName(value = "agent_session_event", autoResultMap = true)
public class AgentSessionEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField(value = "event_type", typeHandler = AgentEventTypeHandler.class)
    private AgentEventType eventType;

    @TableField(value = "priority", typeHandler = AgentEventPriorityHandler.class)
    private AgentEventPriority priority;

    @TableField("data")
    private String data;

    @TableField("tool_name")
    private String toolName;

    @TableField("success")
    private Boolean success;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("created_at")
    private Instant createdAt;

    public AgentSessionEvent() {
    }

    public AgentSessionEvent(String sessionId, Long userId, AgentEventType eventType,
                             AgentEventPriority priority, String data,
                             String toolName, Boolean success, Long durationMs,
                             Instant createdAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.eventType = eventType;
        this.priority = priority;
        this.data = data;
        this.toolName = toolName;
        this.success = success;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }

    // === Getters ===

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public AgentEventType getEventType() { return eventType; }
    public AgentEventPriority getPriority() { return priority; }
    public String getData() { return data; }
    public String getToolName() { return toolName; }
    public Boolean getSuccess() { return success; }
    public Long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }

    // === Setters ===

    public void setId(Long id) { this.id = id; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setEventType(AgentEventType eventType) { this.eventType = eventType; }
    public void setPriority(AgentEventPriority priority) { this.priority = priority; }
    public void setData(String data) { this.data = data; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public void setSuccess(Boolean success) { this.success = success; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
