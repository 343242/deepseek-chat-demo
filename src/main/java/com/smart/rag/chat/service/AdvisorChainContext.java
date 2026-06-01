package com.smart.rag.chat.service;

import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.infrastructure.provider.ModelRouter;
import org.jspecify.annotations.Nullable;

/**
 * 策略构建链时的轻量请求上下文。
 * <p>
 * 策略通过此对象获取请求数据，通过构造注入的 AdvisorInfrastructure 获取共享服务。
 *
 * @param conversationId 隔离后的对话 ID
 * @param request        聊天请求
 * @param userId         用户 ID
 * @param cagContext     CAG 请求上下文（可 null）
 * @param route          模型路由结果 -- 供 Agent guardrails/tokenCountingModel 基于本次请求模型构建
 */
public record AdvisorChainContext(
    String conversationId,
    ChatRequest request,
    Long userId,
    @Nullable RequestContext cagContext,
    ModelRouter.Route route
) {}
