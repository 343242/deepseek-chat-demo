package com.demo.chat.chat.context;

import org.springframework.stereotype.Component;

/**
 * 默认会话上下文解析器
 * <p>
 * 从消息数量推断对话阶段，帮助 LLM 理解当前对话的深度。
 */
@Component
public class DefaultSessionContextResolver implements SessionContextResolver {

    @Override
    public SessionContext resolve(String conversationId, int messageCount) {
        String stage = inferStage(messageCount);
        return new SessionContext(conversationId, messageCount, stage);
    }

    /**
     * 从消息数量推断对话阶段
     *
     * @param messageCount 消息数量
     * @return 对话阶段描述
     */
    String inferStage(int messageCount) {
        if (messageCount == 0) return "首次对话";
        if (messageCount < 5) return "对话初期";
        if (messageCount < 15) return "深入交流";
        return "长对话";
    }
}
