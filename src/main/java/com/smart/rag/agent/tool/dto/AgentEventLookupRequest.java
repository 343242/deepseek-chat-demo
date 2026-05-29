package com.smart.rag.agent.tool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * agentEventLookup Tool 请求参数
 *
 * @param queryText 查询文本
 * @param sessionId 会话 ID（可空，空时使用 workspace 中的用户 ID 检索最近事件）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentEventLookupRequest(
    String queryText,
    String sessionId
) {}
