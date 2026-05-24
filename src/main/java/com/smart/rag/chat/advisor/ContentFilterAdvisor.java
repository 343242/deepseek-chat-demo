package com.smart.rag.chat.advisor;

import com.smart.rag.chat.content.ContentFilterService;
import com.smart.rag.exception.ContentFilteredException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容安全 Advisor
 * <p>
 * 依赖 ContentFilterService 接口，解耦具体敏感词实现。
 * - before：检测用户输入，包含敏感词则拒绝
 * - after：过滤模型输出，替换敏感词（保留原始元数据）
 */
public class ContentFilterAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContentFilterAdvisor.class);
    private static final String BLOCKED_MESSAGE = "您的输入包含不适当的内容，请修改后重试。";

    private final ContentFilterService contentFilterService;

    public ContentFilterAdvisor(ContentFilterService contentFilterService) {
        this.contentFilterService = contentFilterService;
    }

    @Override
    @NonNull
    public String getName() {
        return "ContentFilterAdvisor";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    @NonNull
    public ChatClientRequest before(ChatClientRequest request, @NonNull AdvisorChain chain) {
        String userMessage = extractLastUserMessage(request.prompt().getInstructions());

        if (userMessage != null) {
            // 单次 DFA 扫描：findAll 同时完成检测和收集，避免重复遍历
            List<String> found = contentFilterService.findAll(userMessage);
            if (!found.isEmpty()) {
                log.warn("Sensitive words detected in user input: {} word(s) found", found.size());
                throw new ContentFilteredException(BLOCKED_MESSAGE);
            }
        }

        return request;
    }

    @Override
    @NonNull
    public ChatClientResponse after(ChatClientResponse response, @NonNull AdvisorChain chain) {
        if (response == null || response.chatResponse() == null) {
            return response;
        }
        ChatResponse chatResponse = response.chatResponse();

        List<Generation> filteredGenerations = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            String content = generation.getOutput().getText();
            if (content != null) {
                List<String> found = contentFilterService.findAll(content);
                if (!found.isEmpty()) {
                    String filtered = contentFilterService.replace(content);
                    log.debug("Filtered {} sensitive words in model output", found.size());
                    AssistantMessage newMessage = new AssistantMessage(filtered);
                    // 保留原始 finishReason，添加 contentFiltered 标记
                    ChatGenerationMetadata newMetadata = ChatGenerationMetadata.builder()
                            .finishReason(generation.getMetadata().getFinishReason())
                            .metadata("contentFiltered", true)
                            .build();
                    filteredGenerations.add(new Generation(newMessage, newMetadata));
                } else {
                    filteredGenerations.add(generation);
                }
            } else {
                filteredGenerations.add(generation);
            }
        }

        ChatResponse newChatResponse = new ChatResponse(
                filteredGenerations,
                chatResponse.getMetadata()
        );

        return response.mutate()
                .chatResponse(newChatResponse)
                .build();
    }

    private String extractLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }
}
