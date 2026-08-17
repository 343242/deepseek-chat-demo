package com.smart.rag.mode;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 流式 usage 累计器 — 策略层在 {@code .chatResponse()} 流上累计轮末真实 usage。
 * <p>
 * 仅轮末汇总包携带真实 usage（{@code ChatModelAdapter.buildResponseMetadata} 注入；
 * {@code ChatResponseMetadata} 默认 {@code EmptyUsage} 的 promptTokens=0 视为无），
 * 中间 text/reasoning chunk 跳过——与 {@code UsageRecordingChatModel} 同一检测口径，
 * 保证气泡显示与 usage_event 统计同数。
 * <p>
 * 实例为单流生命周期内对象，仅被 Reactor 串行回调访问，无需并发控制。
 */
public final class StreamUsageAccumulator {

    private long totalTokens = 0;
    private boolean present = false;

    /** 累计一个 ChatResponse 携带的轮末 usage；无真实 usage 时为 no-op。 */
    public void accumulate(@Nullable ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getPromptTokens() == null || usage.getPromptTokens() <= 0
            || usage.getTotalTokens() == null) {
            return;
        }
        totalTokens += usage.getTotalTokens();
        present = true;
    }

    /** 累计值；厂商全程未返回 usage 时为 {@code null}（未知，不做字符估算——每轮显示只给真实值）。 */
    public @Nullable Integer totalTokensOrNull() {
        return present ? (int) totalTokens : null;
    }
}
