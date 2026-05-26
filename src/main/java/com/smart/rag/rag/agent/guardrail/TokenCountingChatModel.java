package com.smart.rag.rag.agent.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * ChatModel 装饰器 -- 累计每轮 Token 用量
 * <p>
 * 包装真实 ChatModel，每次 {@link #call(Prompt)} 调用后从
 * {@link ChatResponse} 的 metadata 中提取并累加 Token 用量。
 * <p>
 * 设计决策（基于 PoC 2 验证结果）：
 * <ul>
 *   <li>外层 Advisor 的 after() 只在整个 ReAct 循环结束后调用一次，无法逐轮获取 usage</li>
 *   <li>因此采用装饰器模式包装 ChatModel，每轮 chatModel.call() 自动累加</li>
 *   <li>{@code ChatResponseMetadata} 默认 usage 为 {@code EmptyUsage}，{@code getPromptTokens()} 返回 0</li>
 *   <li>检测真实 usage 须用 {@code usage.getPromptTokens() > 0}（非 null 检查）</li>
 *   <li>字段名确认: {@code getCompletionTokens()}（非 getGenerationTokens）</li>
 * </ul>
 * <p>
 * 注意：此类是请求级对象（每次 Agent 请求创建新实例），不是 Spring Bean。
 */
public class TokenCountingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TokenCountingChatModel.class);

    /** 字符估算比例：4 个字符约 1 token */
    private static final int CHARS_PER_TOKEN = 4;

    private final ChatModel delegate;
    private long totalPromptTokens = 0;
    private long totalCompletionTokens = 0;
    private boolean estimationUsed = false;

    public TokenCountingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        accumulateUsage(prompt, response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Agent 模式当前为阻塞式，流式支持后续迭代
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    // === Token 累计 ===

    private void accumulateUsage(Prompt prompt, ChatResponse response) {
        if (response == null) {
            return;
        }

        Usage usage = response.getMetadata().getUsage();
        if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
            // 精确计数：模型返回了真实 usage
            int promptTokens = usage.getPromptTokens();
            int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            totalPromptTokens += promptTokens;
            totalCompletionTokens += completionTokens;
            log.debug("TokenCountingChatModel: prompt={}, completion={}, total={}",
                promptTokens, completionTokens, getTotalTokens());
        } else {
            // 字符估算兜底：inputChars/4 + outputChars/4
            long estimatedInput = estimateTokensFromPrompt(prompt);
            long estimatedOutput = estimateTokensFromResponse(response);
            totalPromptTokens += estimatedInput;
            totalCompletionTokens += estimatedOutput;
            estimationUsed = true;
            log.debug("TokenCountingChatModel: estimated input={}, output={}, total={} (estimation)",
                estimatedInput, estimatedOutput, getTotalTokens());
        }
    }

    private long estimateTokensFromPrompt(Prompt prompt) {
        long chars = 0;
        for (Message message : prompt.getInstructions()) {
            if (message.getText() != null) {
                chars += message.getText().length();
            }
        }
        return chars / CHARS_PER_TOKEN;
    }

    private long estimateTokensFromResponse(ChatResponse response) {
        long chars = 0;
        if (response.getResult() != null && response.getResult().getOutput() != null
            && response.getResult().getOutput().getText() != null) {
            chars = response.getResult().getOutput().getText().length();
        }
        return chars / CHARS_PER_TOKEN;
    }

    // === 读取累计 Token ===

    /** 获取累计总 Token 数 */
    public long getTotalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    /** 获取累计 Prompt Token 数 */
    public long getTotalPromptTokens() {
        return totalPromptTokens;
    }

    /** 获取累计 Completion Token 数 */
    public long getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    /** 是否使用了字符估算（未获取到真实 usage） */
    public boolean isEstimationUsed() {
        return estimationUsed;
    }

    /** 获取被装饰的真实 ChatModel */
    public ChatModel getDelegate() {
        return delegate;
    }
}
