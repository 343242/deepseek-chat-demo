package com.smart.rag.infrastructure.llm.client.generic;

import com.smart.rag.infrastructure.llm.ChatCandidate;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.MessageInformation;
import com.smart.rag.infrastructure.llm.ThinkingConfig;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GenericChatClient 请求体注入与阻塞响应提取单测 —— AC1/AC2/AC3/AC4/AC5/AC9/AC10。
 */
@DisplayName("GenericChatClient 思考参数（请求体注入 + 阻塞响应提取）")
class GenericChatClientTest {

    private ChatCandidate candidate;
    private GenericChatClient client;

    @BeforeEach
    void setUp() {
        candidate = new ChatCandidate();
        candidate.setId("test-candidate");
        candidate.setProvider("test-provider");
        candidate.setModel("test-model");
        candidate.setCapability(LlmCapability.CHAT);
        // 共享 OkHttpClient 走实例方法（内部按超时 key 缓存），真实实例即可
        client = new GenericChatClient("http://localhost:1", "/v1/chat/completions",
            "test-key", candidate, new HttpClientFactory());
    }

    private Map<String, Object> body(ChatRequest request) {
        return client.buildRequestBody(request, false);
    }

    // ====== AC3：未配 params.thinking 零注入 ======

    @Test
    @DisplayName("AC3：未配 params.thinking → 请求体无任何思考字段")
    void noThinkingParamsWhenNotConfigured() {
        candidate.setParams(Map.of("temperature", 0.7));

        Map<String, Object> body = body(ChatRequest.builder("你好")
            .temperature(0.7).build());

        assertThat(body).doesNotContainKeys("thinking", "reasoning_effort", "enable_thinking", "thinking_budget");
    }

    @Test
    @DisplayName("AC3：supports-thinking 仅能力声明，不触发注入（未配 params.thinking 时）")
    void supportsThinkingAloneDoesNotInject() {
        candidate.setSupportsThinking(true);

        Map<String, Object> body = body(ChatRequest.of("你好"));

        assertThat(body).doesNotContainKeys("thinking", "reasoning_effort", "enable_thinking", "thinking_budget");
    }

    // ====== AC1/AC9：EFFORT 方言 ======

    @Test
    @DisplayName("AC1：EFFORT 候选配 reasoning-effort → thinking.type=enabled + reasoning_effort")
    void effortDialectInjected() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "effort", "reasoning-effort", "high")));

        Map<String, Object> body = body(ChatRequest.of("分析量子计算"));

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled"));
        assertThat(body.get("reasoning_effort")).isEqualTo("high");
    }

    @Test
    @DisplayName("AC1：EFFORT 未配 reasoning-effort → 仅 thinking.type=enabled（effort 由厂商默认）")
    void effortDialectWithoutEffort() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "effort")));

        Map<String, Object> body = body(ChatRequest.of("你好"));

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled"));
        assertThat(body).doesNotContainKey("reasoning_effort");
    }

    @Test
    @DisplayName("AC9：EFFORT enabled=false → thinking.type=disabled")
    void effortDialectDisabled() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "effort", "enabled", false)));

        Map<String, Object> body = body(ChatRequest.of("你好"));

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(body).doesNotContainKey("reasoning_effort");
    }

    // ====== AC2：BUDGET 方言 ======

    @Test
    @DisplayName("AC2：BUDGET 候选配 thinking-budget → enable_thinking=true + thinking_budget")
    void budgetDialectInjected() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "budget", "thinking-budget", 16000)));

        Map<String, Object> body = body(ChatRequest.of("你好"));

        assertThat(body.get("enable_thinking")).isEqualTo(true);
        assertThat(body.get("thinking_budget")).isEqualTo(16000);
        assertThat(body).doesNotContainKey("thinking");
    }

    @Test
    @DisplayName("AC9：BUDGET enabled=false → enable_thinking=false，无 thinking_budget")
    void budgetDialectDisabled() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "budget", "enabled", false)));

        Map<String, Object> body = body(ChatRequest.of("你好"));

        assertThat(body.get("enable_thinking")).isEqualTo(false);
        assertThat(body).doesNotContainKey("thinking_budget");
    }

    // ====== AC4：per-request 覆盖 ======

    @Test
    @DisplayName("AC4：per-request thinking 覆盖候选默认（覆盖为关闭思考）")
    void perRequestThinkingOverridesCandidateDefault() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "effort", "reasoning-effort", "high")));

        Map<String, Object> body = body(ChatRequest.builder("你好")
            .thinking(ThinkingConfig.disabled()).build());

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "disabled"));
    }

    @Test
    @DisplayName("AC4：per-request thinking 覆盖候选默认（方言仍由候选声明）")
    void perRequestThinkingOverridesToEffort() {
        candidate.setParams(Map.of(
            "thinking", Map.of("dialect", "budget", "thinking-budget", 8000)));

        Map<String, Object> body = body(ChatRequest.builder("你好")
            .thinking(ThinkingConfig.effort("max")).build());

        // 方言仍由候选声明（budget），per-request 只覆盖参数：effort 配置无 budgetTokens → 仅 enable_thinking=true
        assertThat(body.get("enable_thinking")).isEqualTo(true);
        assertThat(body).doesNotContainKey("thinking_budget");
    }

    // ====== AC5：阻塞响应 reasoning_content 提取 ======

    @Test
    @DisplayName("AC5：阻塞响应含 reasoning_content → LlmResponse.reasoningContent() 返回完整文本")
    void parseResponseExtractsReasoningContent() {
        LlmResponse resp = client.parseResponse("""
            {"choices":[{"index":0,"message":{"role":"assistant",
              "reasoning_content":"让我先分析问题本质。",
              "content":"答案是42"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
            """);

        assertThat(resp.content()).isEqualTo("答案是42");
        assertThat(resp.reasoningContent()).isEqualTo("让我先分析问题本质。");
    }

    @Test
    @DisplayName("AC5：阻塞响应无 reasoning_content → reasoningContent() 为空串")
    void parseResponseWithoutReasoningContentReturnsEmpty() {
        LlmResponse resp = client.parseResponse("""
            {"choices":[{"index":0,"message":{"role":"assistant","content":"普通回答"},"finish_reason":"stop"}]}
            """);

        assertThat(resp.reasoningContent()).isEmpty();
    }

    // ====== AC10：工具调用多轮 reasoning_content 回传（buildRequestBody 断点 2） ======

    @Test
    @DisplayName("AC10：assistant+tool_calls 历史消息携带 reasoning_content → 请求体 assistant message 回传该字段")
    void reasoningContentEchoedForToolCallHistory() {
        Map<String, Object> assistantMeta = Map.of(
            "tool_calls", List.of(Map.of(
                "id", "call_1", "type", "function",
                "function", Map.of("name", "hybridSearch", "arguments", "{\"q\":\"Paris\"}"))),
            "reasoning_content", "完整思考过程");
        ChatRequest request = ChatRequest.builder("继续")
            .history(List.of(MessageInformation.assistant("搜索中", assistantMeta)))
            .build();

        Map<String, Object> body = body(request);

        List<?> messages = (List<?>) body.get("messages");
        Map<?, ?> assistantMsg = (Map<?, ?>) messages.get(0);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");
        assertThat(assistantMsg.get("reasoning_content")).isEqualTo("完整思考过程");
        assertThat(assistantMsg.get("tool_calls")).isNotNull();
    }

    @Test
    @DisplayName("AC10：assistant 无 reasoning_content → 不注入该字段（纯多轮不回传）")
    void reasoningContentNotEchoedWhenAbsent() {
        Map<String, Object> assistantMeta = Map.of(
            "tool_calls", List.of(Map.of(
                "id", "call_1", "type", "function",
                "function", Map.of("name", "search", "arguments", "{}"))));
        ChatRequest request = ChatRequest.builder("继续")
            .history(List.of(MessageInformation.assistant("搜索中", assistantMeta)))
            .build();

        Map<String, Object> body = body(request);

        List<?> messages = (List<?>) body.get("messages");
        Map<?, ?> assistantMsg = (Map<?, ?>) messages.get(0);
        assertThat(assistantMsg.containsKey("reasoning_content")).isFalse();
    }
}
