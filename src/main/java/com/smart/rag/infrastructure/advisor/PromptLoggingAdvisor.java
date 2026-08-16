package com.smart.rag.infrastructure.advisor;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 最终提示词日志 Advisor — 在所有前置 Advisor（记忆加载、RAG 动态尾注入等）处理完成后，
 * 打印真正发送给模型的完整消息列表（单轮/多轮、阻塞/流式均覆盖）。
 * <p>
 * 排在链的最末端（ORDER 最大），{@link #before} 收到的即最终请求。
 * 开关：{@code app.chat.prompt-log-enabled}（默认 true）。
 * 日志器独立命名 {@code chat.prompt}，可单独调整级别或输出目的地。
 */
public class PromptLoggingAdvisor implements BaseAdvisor {

    /** 必须大于链内所有 Advisor 的 order（memory=0、RagContextAdvisor=100），确保最后执行 */
    public static final int ORDER = 10_000;

    private static final Logger promptLog = LoggerFactory.getLogger("chat.prompt");

    @Override
    @NonNull
    public String getName() {
        return "PromptLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        if (promptLog.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("\n========== [CHAT] 最终发送给模型的提示词 ==========\n");
            List<Message> messages = request.prompt().getInstructions();
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                sb.append("--- [").append(i).append("] ").append(msg.getMessageType()).append(" ---\n");
                sb.append(msg.getText()).append('\n');
            }
            sb.append("==================================================");
            promptLog.info(sb.toString());
        }
        return request;
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }
}
