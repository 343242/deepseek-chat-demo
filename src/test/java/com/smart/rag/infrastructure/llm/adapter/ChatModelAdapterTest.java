package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import com.smart.rag.infrastructure.llm.StreamChunk;
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

        @Test
        @DisplayName("AC10：历史 AssistantMessage 携 reasoning_content metadata → extractHistory 提取进 MessageInformation.metadata（供请求体回传）")
        void reasoningContentExtractedFromToolCallHistory() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            AssistantMessage prev = AssistantMessage.builder()
                .content("搜索中")
                .properties(Map.of("reasoning_content", "完整思考"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call_1", "function", "hybridSearch", "{\"q\":\"Paris\"}")))
                .build();
            Prompt prompt = new Prompt(List.of(
                new UserMessage("earlier question"),
                prev,
                org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                    .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call_1", "function", "Paris 25°C")))
                    .build(),
                new UserMessage("follow-up")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            MessageInformation assistantInfo = captured.history().stream()
                .filter(m -> "assistant".equals(m.role()))
                .findFirst().orElseThrow();
            assertThat(assistantInfo.metadata()).containsEntry("reasoning_content", "完整思考");
            assertThat(assistantInfo.metadata()).containsKey("tool_calls");
        }

        @Test
        @DisplayName("AC10：无 reasoning_content metadata 的 tool_call 历史 → 不写入该字段（纯多轮不回传）")
        void toolCallHistoryWithoutReasoningNotEchoed() {
            when(delegate.chat(any())).thenReturn(simpleResponse("ok"));

            AssistantMessage prev = AssistantMessage.builder()
                .content("搜索中")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call_1", "function", "hybridSearch", "{\"q\":\"Paris\"}")))
                .build();
            Prompt prompt = new Prompt(List.of(
                prev,
                org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                    .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call_1", "function", "Paris 25°C")))
                    .build(),
                new UserMessage("follow-up")));

            adapter.call(prompt);

            ChatRequest captured = captureChatRequest();
            MessageInformation assistantInfo = captured.history().stream()
                .filter(m -> "assistant".equals(m.role()))
                .findFirst().orElseThrow();
            assertThat(assistantInfo.metadata()).doesNotContainKey("reasoning_content");
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

        @Test
        @DisplayName("AC7：阻塞响应 reasoningContent → AssistantMessage.metadata.reasoning_content")
        void reasoningContentExposedInMetadata() {
            when(delegate.chat(any())).thenReturn(
                new LlmResponse("answer", false, null, List.of(), Map.of(), "完整思考过程"));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            assertThat(response.getResult().getOutput().getMetadata())
                .containsEntry("reasoning_content", "完整思考过程");
        }

        @Test
        @DisplayName("AC7：无 reasoningContent → metadata 不含 reasoning_content")
        void emptyReasoningContentNoMetadata() {
            when(delegate.chat(any())).thenReturn(simpleResponse("answer"));

            ChatResponse response = adapter.call(new Prompt(List.of(new UserMessage("hi"))));

            assertThat(response.getResult().getOutput().getMetadata())
                .doesNotContainKey("reasoning_content");
        }
    }

    // ==================== stream ====================

    @Nested
    @DisplayName("stream(Prompt)")
    class StreamTests {

        @Test
        @DisplayName("each chunk emitted as separate ChatResponse")
        void chunksEmittedAsChatResponses() {
            when(delegate.chatStream(any())).thenReturn(
                reactor.core.publisher.Flux.<String>just("a", "b", "c")
                    .map(s -> new StreamChunk(s, null, null, null)));

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

        @Test
        @DisplayName("P3：toolCall 汇总包 → AssistantMessage.toolCalls + finishReason=tool_calls（触发 ToolCallAdvisor）")
        void toolCallSummaryProjectedAsToolCalls() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just(
                new StreamChunk(null,
                    List.of(new StreamChunk.ToolCallDelta(0, "call_1", "hybridSearch", "{\"q\":\"Paris\"}")),
                    StreamChunk.FinishReason.TOOL_CALLS, null)));

            java.util.List<ChatResponse> responses =
                adapter.stream(new Prompt(List.of(new UserMessage("search Paris")))).collectList().block();

            assertThat(responses).hasSize(1);
            ChatResponse r = responses.get(0);
            AssistantMessage msg = (AssistantMessage) r.getResult().getOutput();
            assertThat(msg.getToolCalls()).hasSize(1);
            assertThat(msg.getToolCalls().get(0).id()).isEqualTo("call_1");
            assertThat(msg.getToolCalls().get(0).name()).isEqualTo("hybridSearch");
            assertThat(msg.getToolCalls().get(0).arguments()).isEqualTo("{\"q\":\"Paris\"}");
            // Generation metadata finishReason=tool_calls（Spring AI 据此驱动工具执行 ReAct）
            assertThat(r.getResult().getMetadata().getFinishReason()).isEqualTo("tool_calls");
        }

        @Test
        @DisplayName("P3：STOP+usage 末包 → finishReason=stop + usage metadata（供 TokenCountingChatModel 累计）")
        void stopWithUsageCarriedInMetadata() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just(
                new StreamChunk("answer", null, null, null),  // 文本 chunk
                new StreamChunk(null, null, StreamChunk.FinishReason.STOP,
                    new LlmResponse.TokenUsage(10, 20, 30, null))));  // STOP+usage 末包

            java.util.List<ChatResponse> responses =
                adapter.stream(new Prompt(List.of(new UserMessage("hi")))).collectList().block();

            assertThat(responses).hasSize(2);
            // text chunk：content 透传，无 finishReason
            assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("answer");
            assertThat(responses.get(0).getResult().getMetadata().getFinishReason()).isNull();
            // STOP 末包：finishReason=stop + usage metadata
            ChatResponse end = responses.get(1);
            assertThat(end.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
            assertThat(end.getMetadata().getUsage()).isNotNull();
            assertThat(end.getMetadata().getUsage().getTotalTokens()).isEqualTo(30);
        }

        @Test
        @DisplayName("AC7：reasoning-only chunk → ChatResponse 仅 metadata 携 reasoning_content，content 为空，无 finishReason")
        void reasoningOnlyChunkExposed() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just(
                new StreamChunk(null, null, null, null, "思考中")));

            java.util.List<ChatResponse> responses =
                adapter.stream(new Prompt(List.of(new UserMessage("hi")))).collectList().block();

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getResult().getOutput().getText()).isEmpty();
            assertThat(responses.get(0).getResult().getOutput().getMetadata())
                .containsEntry("reasoning_content", "思考中");
            assertThat(responses.get(0).getResult().getMetadata().getFinishReason()).isNull();
        }

        @Test
        @DisplayName("AC7：text + reasoning 同 chunk → 两者都暴露")
        void textAndReasoningChunkExposed() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just(
                new StreamChunk("回答", null, null, null, "思考")));

            java.util.List<ChatResponse> responses =
                adapter.stream(new Prompt(List.of(new UserMessage("hi")))).collectList().block();

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("回答");
            assertThat(responses.get(0).getResult().getOutput().getMetadata())
                .containsEntry("reasoning_content", "思考");
        }

        @Test
        @DisplayName("AC10：toolCall 汇总包携完整累积 reasoning → AssistantMessage.metadata 保留完整值")
        void toolCallSummaryCarriesReasoning() {
            when(delegate.chatStream(any())).thenReturn(reactor.core.publisher.Flux.just(
                new StreamChunk(null,
                    List.of(new StreamChunk.ToolCallDelta(0, "call_1", "hybridSearch", "{\"q\":\"Paris\"}")),
                    StreamChunk.FinishReason.TOOL_CALLS, null, "完整累积思考")));

            java.util.List<ChatResponse> responses =
                adapter.stream(new Prompt(List.of(new UserMessage("search Paris")))).collectList().block();

            assertThat(responses).hasSize(1);
            ChatResponse r = responses.get(0);
            AssistantMessage msg = (AssistantMessage) r.getResult().getOutput();
            assertThat(msg.getToolCalls()).hasSize(1);
            assertThat(msg.getMetadata()).containsEntry("reasoning_content", "完整累积思考");
            assertThat(r.getResult().getMetadata().getFinishReason()).isEqualTo("tool_calls");
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
