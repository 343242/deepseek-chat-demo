package com.smart.rag.mode;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 对话模式策略的共享工具方法。
 * <p>
 * 归属于 mode 包：供 chat（AbstractModeStrategy）与 agent（AgentModeStrategy）
 * 共用，避免 agent 反向依赖 chat.mode.AbstractModeStrategy 造成循环。
 */
public final class ModeSupport {

    private ModeSupport() {}

    /**
     * 从 Spring AI ChatResponse 安全提取文本内容。
     * <p>
     * null 安全：response / result / output 任一为 null 时返回空串。
     */
    public static String extractContent(ChatResponse response) {
        if (response == null) {
            return "";
        }
        Generation gen = response.getResult();
        return gen != null && gen.getOutput() != null ? gen.getOutput().getText() : "";
    }
}
