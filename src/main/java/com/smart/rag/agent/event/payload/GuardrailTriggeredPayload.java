package com.smart.rag.agent.event.payload;

/**
 * GUARDRAIL_TRIGGERED 事件 payload
 * <p>
 * 记录护栏触发的详细信息，用于审计和会话恢复。
 *
 * @param guardrailName 护栏名称（如 "token_budget", "content_filter"）
 * @param reason        触发原因描述
 * @param action        采取的动作（如 "stop", "degrade", "retry"）
 */
public record GuardrailTriggeredPayload(
    String guardrailName,
    String reason,
    String action
) {}
