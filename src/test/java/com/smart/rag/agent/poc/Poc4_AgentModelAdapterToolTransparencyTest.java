package com.smart.rag.agent.poc;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PoC 4 ChatModelAdapter tool transparency (post-fix)")
class Poc4_AgentModelAdapterToolTransparencyTest {
    @Mock private ChatCapable delegate;
    private ChatModelAdapter adapter;

    @BeforeEach
    void setUp() { adapter = new ChatModelAdapter(delegate); }

    @Test
    @DisplayName("RESPONSE block toolCalls preserved (Fix A)")
    void responseToolCallsPreserved() {
        LlmResponse.ToolCall tc = new LlmResponse.ToolCall("call_1", "hybridSearch", "{}");
        LlmResponse fake = new LlmResponse("draft", false, null, List.of(tc), Map.of());
        when(delegate.chat(any())).thenReturn(fake);
        ChatResponse resp = adapter.call(new Prompt(new UserMessage("hi")));
        var got = resp.getResult().getOutput().getToolCalls();
        boolean preserved = got != null && !got.isEmpty();
        System.out.println("POC_RESULT response_block toolCallsPreserved=" + preserved);
        assertThat(preserved).as("post-fix: LlmResponse.toolCalls should be preserved").isTrue();
    }

    @Test
    @DisplayName("REQUEST block toolCallbacks forwarded to ChatRequest.tools (Fix B-i)")
    void requestToolsForwarded() {
        when(delegate.chat(any())).thenReturn(new LlmResponse("ok", false, null, List.of(), Map.of()));
        var cb = FunctionToolCallback.builder("search", (String input, ToolContext ctx) -> "x").description("search").inputType(String.class).build();
        adapter.call(new Prompt(List.of(new UserMessage("hi")), ToolCallingChatOptions.builder().toolCallbacks(cb).build()));
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(delegate).chat(captor.capture());
        boolean forwarded = captor.getValue().tools() != null && !captor.getValue().tools().isEmpty();
        System.out.println("POC_RESULT request_block toolsForwarded=" + forwarded);
        assertThat(forwarded).as("post-fix: toolCallbacks should be forwarded to ChatRequest.tools").isTrue();
    }

    @Test
    @DisplayName("STREAM chatStream text-only chunks")
    void streamIsTextOnly() {
        when(delegate.chatStream(any())).thenReturn(Flux.just("a", "b", "c"));
        List<ChatResponse> responses = adapter.stream(new Prompt(new UserMessage("hi"))).collectList().block();
        boolean anyToolCall = responses != null && responses.stream().anyMatch(r -> { var tc = r.getResult().getOutput().getToolCalls(); return tc != null && !tc.isEmpty(); });
        int size = responses == null ? 0 : responses.size();
        System.out.println("POC_RESULT stream chunks=" + size + " anyToolCall=" + anyToolCall);
        assertThat(anyToolCall).isFalse();
    }
}
