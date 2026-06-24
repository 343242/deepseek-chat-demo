package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.ChatTool;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import com.smart.rag.infrastructure.llm.StreamChunk;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring AI ChatModel 适配器。
 * <p>
 * Fix B-i：工具透传。
 * <ul>
 *   <li>请求侧 extractTools：从 Prompt options 的 ToolCallingChatOptions 取 ToolCallback，转成 ChatTool 透传给厂商</li>
 *   <li>响应侧 wrapAsChatResponse：把 LlmResponse.toolCalls 回灌进 AssistantMessage.toolCalls，供 ToolCallAdvisor 驱动 ReAct 循环</li>
 * </ul>
 */
public class ChatModelAdapter implements ChatModel {

    private final ChatCapable delegate;

    public ChatModelAdapter(ChatCapable delegate) {
        this.delegate = delegate;
    }

    public ChatCapable delegate() { return delegate; }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        LlmResponse llmResp = delegate.chat(request);
        return wrapAsChatResponse(llmResp);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        // P3：StreamChunk → ChatResponse 投影（design §4 + §0 #3：边界层不累积，SSE 层已合并 toolCalls 分片）。
        //  - 轮末汇总包(完整 toolCalls) → AssistantMessage.toolCalls + finishReason="tool_calls"
        //    → ToolCallAdvisor.adviseStream 检测并执行工具，驱动流式 ReAct（Poc6 验证 streamCount=2）。
        //  - 文本 chunk → AssistantMessage(content) 透传（保 TTFT）；STOP/LENGTH 末包携带 finishReason + usage。
        return delegate.chatStream(request)
            .map(chunk -> {
                if (chunk.hasToolCall()) {
                    String content = chunk.text() != null ? chunk.text() : "";
                    AssistantMessage msg = new ToolCallAssistantMessage(content,
                        toSpringToolCallsFromDeltas(chunk.toolCalls()));
                    Generation gen = new Generation(msg, ChatGenerationMetadata.builder()
                        .finishReason("tool_calls").build());
                    return new ChatResponse(List.of(gen), buildResponseMetadata(chunk));
                }
                String content = chunk.hasText() ? chunk.text() : "";
                AssistantMessage msg = new AssistantMessage(content);
                Generation gen = new Generation(msg, buildGenerationMetadata(chunk.finishReason()));
                return new ChatResponse(List.of(gen), buildResponseMetadata(chunk));
            });
    }

    private ChatResponse wrapAsChatResponse(LlmResponse llmResp) {
        String content = llmResp.content() != null ? llmResp.content() : "";
        List<AssistantMessage.ToolCall> springToolCalls = toSpringToolCalls(llmResp.toolCalls());
        AssistantMessage assistantMsg = springToolCalls.isEmpty()
            ? new AssistantMessage(content)
            : new ToolCallAssistantMessage(content, springToolCalls);
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

    private static List<AssistantMessage.ToolCall> toSpringToolCalls(List<LlmResponse.ToolCall> tcs) {
        if (tcs == null || tcs.isEmpty()) return List.of();
        List<AssistantMessage.ToolCall> out = new ArrayList<>(tcs.size());
        for (LlmResponse.ToolCall tc : tcs) {
            out.add(new AssistantMessage.ToolCall(
                tc.id() != null ? tc.id() : "",
                "function",
                tc.name(),
                tc.arguments() != null ? tc.arguments() : ""));
        }
        return out;
    }

    /** P3：StreamChunk.ToolCallDelta → AssistantMessage.ToolCall（流式汇总包回灌，SSE 层已合并分片）。 */
    private static List<AssistantMessage.ToolCall> toSpringToolCallsFromDeltas(List<StreamChunk.ToolCallDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) return List.of();
        List<AssistantMessage.ToolCall> out = new ArrayList<>(deltas.size());
        for (StreamChunk.ToolCallDelta d : deltas) {
            out.add(new AssistantMessage.ToolCall(
                d.id() != null ? d.id() : "",
                "function",
                d.name(),
                d.arguments() != null ? d.arguments() : ""));
        }
        return out;
    }

    /** P3：StreamChunk.FinishReason → Spring AI finishReason（null → 空 metadata，中间 chunk 无 finishReason）。 */
    private static ChatGenerationMetadata buildGenerationMetadata(StreamChunk.FinishReason fr) {
        if (fr == null) return ChatGenerationMetadata.builder().build();
        return ChatGenerationMetadata.builder().finishReason(switch (fr) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_CALLS -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
        }).build();
    }

    /** P3：轮末 usage → ChatResponseMetadata（供 TokenCountingChatModel.stream 累计，P4b）。 */
    private static ChatResponseMetadata buildResponseMetadata(StreamChunk chunk) {
        ChatResponseMetadata.Builder b = ChatResponseMetadata.builder();
        if (chunk.usage() != null) {
            b.usage(new DefaultUsage(
                chunk.usage().promptTokens(),
                chunk.usage().completionTokens(),
                chunk.usage().totalTokens()));
        }
        return b.build();
    }

    /** 子类访问 AssistantMessage 的 protected 四参构造器，用于回灌 toolCalls */
    private static final class ToolCallAssistantMessage extends AssistantMessage {
        ToolCallAssistantMessage(String content, List<AssistantMessage.ToolCall> toolCalls) {
            super(content, Map.of(), toolCalls, List.of());
        }
    }

    private ChatRequest extractChatRequest(Prompt prompt) {
        String systemPrompt = extractSystemPrompt(prompt);
        List<MessageInformation> history = extractHistory(prompt);
        List<ChatTool> tools = extractTools(prompt);
        return new ChatRequest(prompt.getContents(), systemPrompt, history,
            null, null, null, Map.of(), tools);
    }

    /** Fix B-i 请求侧：从 ToolCallingChatOptions 提取 ToolCallback 转 ChatTool */
    private List<ChatTool> extractTools(Prompt prompt) {
        ChatOptions opts = prompt.getOptions();
        if (!(opts instanceof ToolCallingChatOptions tco)) return List.of();
        List<ToolCallback> callbacks = tco.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) return List.of();
        List<ChatTool> tools = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            tools.add(new ChatTool(def.name(), def.description(), def.inputSchema()));
        }
        return tools;
    }

    private String extractSystemPrompt(Prompt prompt) {
        if (prompt.getInstructions() == null) return null;
        for (var msg : prompt.getInstructions()) {
            if (msg instanceof SystemMessage sm) return sm.getText();
        }
        return null;
    }

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
            if (m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    Map<String, Object> fn = new java.util.LinkedHashMap<>();
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.arguments() != null ? tc.arguments() : "");
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("id", tc.id());
                    entry.put("type", "function");
                    entry.put("function", fn);
                    tcs.add(entry);
                }
                builder.add(MessageInformation.assistant(am.getText() != null ? am.getText() : "", Map.of("tool_calls", tcs)));
            } else if (m instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                for (org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    builder.add(MessageInformation.tool(tr.id(), tr.responseData()));
                }
            } else {
                builder.add(MessageInformation.of(m.getMessageType().name().toLowerCase(), m.getText()));
            }
        }
        return Collections.unmodifiableList(builder);
    }

    private int findLastUserIndex(List<org.springframework.ai.chat.messages.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) return i;
        }
        return -1;
    }
}
