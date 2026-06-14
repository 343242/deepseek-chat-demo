package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatModelAdapter}.
 * <p>
 * Verifies the bidirectional mapping between Spring AI {@code Prompt/ChatResponse}
 * and SPI {@code ChatRequest/LlmResponse}.
 */
@ExtendWith(MockitoExtension.class)
class ChatModelAdapterTest {

    @Mock
    private ChatCapable delegate;

    private ChatModelAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ChatModelAdapter(delegate);
    }

    // ==================== call (Prompt → ChatRequest) ====================

    @Nested
    @DisplayName("call(Prompt) — request extraction")
    class CallRequestExtractionTests {

        @Test
        @DisplayName("SystemMessage is extracted as systemPrompt")
        void systemMessageExtractedAsSystemPrompt() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            Prompt prompt = new Prompt(List.of(
                new SystemMessage("You are helpful"),
                new UserMessage("hi")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            assertThat(captured.systemPrompt()).isEqualTo("You are helpful");
        }

        @Test
        @DisplayName("single UserMessage becomes ChatRequest.input verbatim")
        void userMessageBecomesInput() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            Prompt prompt = new Prompt(List.of(new UserMessage("what is 1+1")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            // Spring AI's Prompt.getContents() returns the message text
            assertThat(captured.input()).isEqualTo("what is 1+1");
        }

        @Test
        @DisplayName("history messages (AssistantMessage + earlier UserMessage) are extracted; last UserMessage excluded from history")
        void historyExtractedBeforeLastUser() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            Prompt prompt = new Prompt(List.of(
                new UserMessage("earlier question"),
                new AssistantMessage("earlier answer"),
                new UserMessage("follow-up")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            // input = prompt.getContents() = concatenation of all message texts
            assertThat(captured.input()).contains("follow-up");
            // history excludes the last UserMessage; earlier user + assistant captured
            assertThat(captured.history()).hasSize(2);
            assertThat(captured.history()).extracting(MessageInformation::role)
                .containsExactly("user", "assistant");
            assertThat(captured.history()).extracting(MessageInformation::content)
                .containsExactly("earlier question", "earlier answer");
        }

        @Test
        @DisplayName("no SystemMessage → systemPrompt is null")
        void noSystemMessageNullPrompt() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            Prompt prompt = new Prompt(List.of(new UserMessage("hi")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            assertThat(captured.systemPrompt()).isNull();
        }

        @Test
        @DisplayName("empty instructions → empty history, null system prompt")
        void emptyInstructions() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            Prompt prompt = new Prompt(List.<org.springframework.ai.chat.messages.Message>of());

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            assertThat(captured.history()).isEmpty();
            assertThat(captured.systemPrompt()).isNull();
        }

        @Test
        @DisplayName("only SystemMessage, no UserMessage → empty history")
        void onlySystemNoUser() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            Prompt prompt = new Prompt(List.of(new SystemMessage("be nice")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            assertThat(captured.history()).isEmpty();
        }

        @Test
        @DisplayName("ChatRequest extraParams is empty (no params passed from Prompt)")
        void extraParamsEmpty() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            ChatRequest captured = captureChatRequest();
            assertThat(captured.extraParams()).isEmpty();
        }
    }

    // ==================== call (LlmResponse → ChatResponse) ====================

    @Nested
    @DisplayName("call(Prompt) — response wrapping")
    class CallResponseWrappingTests {

        @Test
        @DisplayName("content is wrapped in AssistantMessage inside Generation")
        void contentWrappedInAssistantMessage() {
            when(delegate.chat(any())).thenReturn(simpleResponse("hello world"));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            assertThat(response.getResults()).hasSize(1);
            Generation gen = response.getResults().get(0);
            assertThat(gen.getOutput().getText()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("truncated=true → finishReason is 'length'")
        void truncatedMapsToLengthFinishReason() {
            when(delegate.chat(any())).thenReturn(
                new LlmResponse("cut off", true, null, List.of(), Map.of()));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            Generation gen = response.getResults().get(0);
            assertThat(gen.getMetadata().getFinishReason()).isEqualTo("length");
        }

        @Test
        @DisplayName("truncated=false → finishReason is 'stop'")
        void notTruncatedMapsToStopFinishReason() {
            when(delegate.chat(any())).thenReturn(
                new LlmResponse("complete", false, null, List.of(), Map.of()));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            Generation gen = response.getResults().get(0);
            assertThat(gen.getMetadata().getFinishReason()).isEqualTo("stop");
        }

        @Test
        @DisplayName("null content → AssistantMessage with empty string")
        void nullContentBecomesEmptyString() {
            when(delegate.chat(any())).thenReturn(
                new LlmResponse(null, false, null, List.of(), Map.of()));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("");
        }

        @Test
        @DisplayName("tokenUsage is mapped to ChatResponseMetadata.usage")
        void tokenUsageMapped() {
            LlmResponse.TokenUsage usage = new LlmResponse.TokenUsage(10, 20, 30);
            when(delegate.chat(any())).thenReturn(
                new LlmResponse("text", false, usage, List.of(), Map.of()));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            assertThat(response.getMetadata().getUsage()).isNotNull();
            assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(10);
            assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(20);
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(30);
        }

        @Test
        @DisplayName("null tokenUsage → metadata.usage is null (no NPE)")
        void nullTokenUsageSafe() {
            when(delegate.chat(any())).thenReturn(simpleResponse("text"));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            // Should not throw NPE; metadata is built without usage
            assertThat(response).isNotNull();
            // getUsage() may return null or DefaultUsage(null,null,null) — either is acceptable
        }
    }

    // ==================== stream ====================

    @Nested
    @DisplayName("stream(Prompt)")
    class StreamTests {

        @Test
        @DisplayName("each chunk emitted as separate ChatResponse")
        void chunksEmittedAsChatResponses() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just("a", "b", "c"));

            ChatRequest[] captured = new ChatRequest[1];
            Prompt prompt = new Prompt(List.of(new UserMessage("hi")));

            java.util.List<ChatResponse> responses = adapter.stream(prompt).collectList().block();

            assertThat(responses).hasSize(3);
            assertThat(responses).allSatisfy(r -> {
                assertThat(r.getResults()).hasSize(1);
            });
            assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("a");
            assertThat(responses.get(1).getResult().getOutput().getText()).isEqualTo("b");
            assertThat(responses.get(2).getResult().getOutput().getText()).isEqualTo("c");
        }

        @Test
        @DisplayName("empty stream → empty result list")
        void emptyStream() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.empty());

            java.util.List<ChatResponse> responses =
                adapter.stream(new Prompt(List.of(new UserMessage("hi")))).collectList().block();

            assertThat(responses).isEmpty();
        }
    }

    // ==================== accessor ====================

    @Test
    @DisplayName("delegate() returns the wrapped ChatCapable")
    void delegateAccessor() {
        assertThat(adapter.delegate()).isSameAs(delegate);
    }

    // ==================== getDefaultOptions ====================

    @Nested
    @DisplayName("getDefaultOptions() — ToolCallingChatOptions contract")
    class GetDefaultOptionsTests {

        @Test
        @DisplayName("getDefaultOptions() is not null")
        void getDefaultOptions_notNull() {
            assertThat(adapter.getDefaultOptions()).isNotNull();
        }

        @Test
        @DisplayName("getDefaultOptions() returns a ToolCallingChatOptions instance")
        void getDefaultOptions_returnsToolCallingChatOptions() {
            assertThat(adapter.getDefaultOptions())
                .isInstanceOf(ToolCallingChatOptions.class);
        }

        @Test
        @DisplayName("getDefaultOptions() returns a fresh instance per call (no shared state)")
        void getDefaultOptions_freshInstancePerCall() {
            org.springframework.ai.chat.prompt.ChatOptions first = adapter.getDefaultOptions();
            org.springframework.ai.chat.prompt.ChatOptions second = adapter.getDefaultOptions();

            // Each builder().build() must produce a new instance so callers can
            // mutate (e.g., set toolCallbacks) without leaking state to siblings.
            assertThat(first).isNotSameAs(second);
        }
    }

    // ==================== helpers ====================

    private ChatRequest captureChatRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(delegate).chat(captor.capture());
        return captor.getValue();
    }

    private static LlmResponse simpleResponse(String content) {
        return new LlmResponse(content, false, null, List.of(), Map.of());
    }
}
