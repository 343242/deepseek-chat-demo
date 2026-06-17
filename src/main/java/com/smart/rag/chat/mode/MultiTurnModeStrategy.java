package com.smart.rag.chat.mode;

/**
 * MULTI_TURN 模式策略 — 多轮对话，自动维护记忆和上下文
 */
public class MultiTurnModeStrategy implements ChatModeStrategy {

    private final boolean thinkingEnabled;

    public MultiTurnModeStrategy() {
        this(false);
    }

    public MultiTurnModeStrategy(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    @Override
    public ChatMode getMode() {
        return ChatMode.MULTI_TURN;
    }

    @Override
    public boolean isMemoryEnabled() {
        return true;
    }

    @Override
    public boolean isContextEnabled() {
        return true;
    }

    @Override
    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }
}
