package com.smart.rag.chat.mode;

/**
 * 对话模式枚举
 * <p>
 * SIMPLE: 单轮直接调用 LLM，无上下文记忆，适合 FAQ、简单问答场景。<br>
 * MULTI_TURN: 多轮对话模式，自动维护会话上下文与记忆系统，支持思考过程输出（enableThinking）。
 * <p>
 * 默认模式为 SIMPLE，多轮需显式指定。
 */
public enum ChatMode {

    /** 单轮对话 — 无记忆、无上下文注入 */
    SIMPLE,

    /** 多轮对话 — 自动维护会话记忆，支持 enableThinking */
    MULTI_TURN;

    /**
     * 从字符串安全解析，无法识别时返回默认值 SIMPLE。
     */
    public static ChatMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return SIMPLE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SIMPLE;
        }
    }
}
