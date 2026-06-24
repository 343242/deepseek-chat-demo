package com.smart.rag.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.SignalType;

/**
 * 流式收尾落库 helper — 封装 StreamResult 完成时的落库逻辑（design §5，P4-1）。
 * <p>
 * 抽自 {@code AbstractModeStrategy#onStreamComplete}，让 AGENT 流式（{@code AgentModeStrategy}
 * 不继承 {@code AbstractModeStrategy}）也能用与 SIMPLE/MULTI_TURN <b>逐字一致</b>的落库语义：
 * <ul>
 *   <li>ON_COMPLETE → {@link ChatMessagePublisher#publishMessageSave} 落 user + finalAssistant</li>
 *   <li>ON_ERROR / CANCEL → {@link ChatConversationHelper#savePartialResponse} 存部分响应</li>
 * </ul>
 * <p>
 * <b>静态工具类</b>：调用方各自持有 {@code chatMessagePublisher} / {@code conversationHelper}
 * （AbstractModeStrategy 经构造器，AgentModeStrategy 经 {@code @Component} 注入），无需新增 bean
 * 或改动既有构造器签名——Simple/MultiTurn 落库路径零回归。
 */
public final class StreamCompletionHelper {

    private static final Logger log = LoggerFactory.getLogger(StreamCompletionHelper.class);

    private StreamCompletionHelper() {}

    /**
     * 流式终止信号统一落库。
     *
     * @param ctx       策略执行上下文（conversationId / request / candidateId / elapsed）
     * @param content   流式累计的完整文本（已截断保护）
     * @param signal    终止信号（ON_COMPLETE / ON_ERROR / CANCEL）
     * @param publisher 消息落库
     * @param helper    会话辅助（部分响应保存）
     */
    public static void onComplete(StrategyExecutionContext ctx, String content, SignalType signal,
                                  ChatMessagePublisher publisher, ChatConversationHelper helper) {
        switch (signal) {
            case ON_COMPLETE -> publisher.publishMessageSave(ctx.conversationId(),
                ctx.request().message(), content,
                ctx.candidateId(), null, ctx.elapsed());
            case ON_ERROR, CANCEL -> {
                log.warn("Stream {} for conversation {}: collected {} chars",
                    signal, ctx.conversationId(), content.length());
                helper.savePartialResponse(ctx.conversationId(), content);
            }
            default -> { }
        }
    }
}
