package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring AI ChatModel 适配器
 * <p>
 * 将任何 {@code ChatCapable} 实例适配为 Spring AI {@code ChatModel}。
 * 这是 ChatCapable 与 ChatModel 之间桥接代码的唯一存放位置。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>ISP — ChatCapable 不被迫继承 ChatModel 的所有方法</li>
 *   <li>LSP — 适配器是独立的 ChatModel 实现，不影响 ChatCapable 的契约</li>
 *   <li>SRP — 桥接逻辑（Prompt→ChatRequest、LlmResponse→ChatResponse）集中在此</li>
 * </ul>
 */
public class ChatModelAdapter implements ChatModel {

    private final ChatCapable delegate;

    public ChatModelAdapter(ChatCapable delegate) {
        this.delegate = delegate;
    }

    public ChatCapable delegate() { return delegate; }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        LlmResponse llmResp = delegate.chat(request);
        return wrapAsChatResponse(llmResp);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        return delegate.chatStream(request)
            .map(chunk -> new ChatResponse(
                List.of(new Generation(new AssistantMessage(chunk)))));
    }

    private ChatResponse wrapAsChatResponse(LlmResponse llmResp) {
        AssistantMessage assistantMsg = new AssistantMessage(
            llmResp.content() != null ? llmResp.content() : "");
        Generation generation = new Generation(assistantMsg,
            ChatGenerationMetadata.builder()
                .finishReason(llmResp.truncated() ? "length" : "stop")
                .build());
        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder();
        if (llmResp.tokenUsage() != null) {
            metaBuilder.usage(new DefaultUsage(
                llmResp.tokenUsage().promptTokens(),
                llmResp.tokenUsage().completionTokens(),
                llmResp.tokenUsage().totalTokens()));
        }
        return new ChatResponse(List.of(generation), metaBuilder.build());
    }

    private ChatRequest extractChatRequest(Prompt prompt) {
        String systemPrompt = extractSystemPrompt(prompt);
        List<MessageInformation> history = extractHistory(prompt);
        return new ChatRequest(prompt.getContents(), systemPrompt, history,
            null, null, null, Map.of());
    }

    /** 从 Prompt instructions 中提取第一条 SystemMessage 的文本 */
    private String extractSystemPrompt(Prompt prompt) {
        if (prompt.getInstructions() == null) return null;
        for (var msg : prompt.getInstructions()) {
            if (msg instanceof SystemMessage sm) return sm.getText();
        }
        return null;
    }

    /** 从 Prompt instructions 中提取非系统、非最后一条 UserMessage 的历史记录 */
    private List<MessageInformation> extractHistory(Prompt prompt) {
        if (prompt.getInstructions() == null || prompt.getInstructions().isEmpty()) {
            return List.of();
        }
        var nonSystemMessages = prompt.getInstructions().stream()
            .filter(m -> !(m instanceof SystemMessage))
            .toList();
        if (nonSystemMessages.isEmpty()) return List.of();

        int lastUserIdx = findLastUserIndex(nonSystemMessages);
        if (lastUserIdx < 0) return List.of();

        var builder = new ArrayList<MessageInformation>(nonSystemMessages.size() - 1);
        for (int i = 0; i < nonSystemMessages.size(); i++) {
            if (i == lastUserIdx) continue;
            var m = nonSystemMessages.get(i);
            builder.add(MessageInformation.of(
                m.getMessageType().name().toLowerCase(), m.getText()));
        }
        return Collections.unmodifiableList(builder);
    }

    /** 从消息列表中从后往前查找最后一条 UserMessage 的索引，未找到返回 -1 */
    private int findLastUserIndex(List<org.springframework.ai.chat.messages.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) return i;
        }
        return -1;
    }
}
