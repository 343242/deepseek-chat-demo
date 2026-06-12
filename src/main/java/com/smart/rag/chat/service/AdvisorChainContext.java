package com.smart.rag.chat.service;

import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import org.jspecify.annotations.Nullable;

/**
 * 策略构建链时的轻量请求上下文。
 */
public record AdvisorChainContext(
    String conversationId,
    ChatRequest request,
    Long userId,
    @Nullable RequestContext cagContext,
    /** 候选模型 ID（对应 YAML candidate.id） */
    String candidateId
) {}
