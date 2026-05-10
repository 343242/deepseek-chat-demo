package com.demo.chat.chat.mode;

/**
 * SIMPLE 模式策略 — 单轮对话，无记忆、无上下文、无思考
 */
public class SimpleModeStrategy implements ChatModeStrategy {

    @Override
    public ChatMode getMode() {
        return ChatMode.SIMPLE;
    }

    @Override
    public boolean isMemoryEnabled() {
        return false;
    }

    @Override
    public boolean isContextEnabled() {
        return false;
    }

    @Override
    public boolean isThinkingEnabled() {
        return false;
    }
}
