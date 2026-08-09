package com.smart.rag.chat.service;

import com.smart.rag.mode.StrategyExecutionContext;
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
 *   <li>ON_ERROR / CANCEL → <b>不落库</b>（design chat-stream-cancel.md §5.2，取消即作废）</li>
 * </ul>
 * <p>
 * <b>usage 解耦</b>：usage 记录由策略层 doFinally 内 {@code usageRecorded CAS → recordUsage} 独立执行，
 * 不经过本 helper。因此 CANCEL 下「消息不落库」与「usage 照常记录」并存（design §5.2）。
 * <p>
 * <b>静态工具类</b>：调用方各自持有 {@code chatMessagePublisher} / {@code conversationHelper}
 * （AbstractModeStrategy 经构造器，AgentModeStrategy 经 {@code @Component} 注入），无需新增 bean
 * 或改动既有构造器签名——Simple/MultiTurn 落库路径零回归。
 */
public final class StreamCompletionHelper {

    private static final Logger log = LoggerFactory.getLogger(StreamCompletionHelper.class);

    private StreamCompletionHelper() {}

    /**
     * 流式终止信号统一处理。
     * <p>
     * ON_COMPLETE 落库；ON_ERROR/CANCEL 作废不落库（design chat-stream-cancel.md §5.2）。
     * 取消即作废——用户须重新生成（重发同一消息）或复述才能继续，会话历史保持干净，
     * 避免半截回复污染多轮记忆。usage 由策略层独立记录，不受此影响。
     *
     * @param ctx       策略执行上下文（conversationId / request / candidateId / elapsed）
     * @param content   流式累计的完整文本（已截断保护）
     * @param signal    终止信号（ON_COMPLETE / ON_ERROR / CANCEL）
     * @param publisher 消息落库
     * @param helper    会话辅助（保留参数，ON_ERROR/CANCEL 不再调用其 savePartialResponse）
     */
    public static void onComplete(StrategyExecutionContext ctx, String content, SignalType signal,
                                  ChatMessagePublisher publisher, ChatConversationHelper helper) {
        switch (signal) {
            case ON_COMPLETE -> publisher.publishMessageSave(ctx.conversationId(),
                ctx.request().message(), content,
                ctx.candidateId(), null, ctx.elapsed());
            case ON_ERROR, CANCEL -> {
                // 取消即作废：不落库（design chat-stream-cancel.md §5.2）。
                // 含用户主动取消与意外断连——不区分 reason，统一作废，用户重新生成。
                // 这保证多轮记忆 advisor 不会把残缺回复喂给下一轮。
                // usage 由策略层 doFinally 独立记录（token 已消耗），与此处不落库并存。
                log.info("Stream {} for conversation {}: content discarded ({} chars, not persisted)",
                    signal, ctx.conversationId(), content.length());
            }
            default -> { }
        }
    }
}
