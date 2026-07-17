package com.smart.rag.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.config.AgentRagProperties;
import com.smart.rag.mode.AgentIntent;
import com.smart.rag.mode.IntentResult;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IntentClassifier 单测 -- 覆盖阻塞式 {@code classify} 与流式 {@code classifyStream} 两条同源路径。
 * <p>
 * 流式测试通过 mock {@link ChatCapable#chatStream(ChatRequest)} 返回分片 JSON，验证
 * {@code ChatModelAdapter.stream -> ChatClient.stream().content()} 聚合完整 JSON 后正确解析意图。
 */
@ExtendWith(MockitoExtension.class)
class IntentClassifierTest {

    private static final String INTENT_MODEL = "intent-model";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private LlmClientRegistry llmRegistry;
    @Mock private AgentRagProperties properties;
    @Mock private ChatCapable capable;

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        when(properties.intentModel()).thenReturn(INTENT_MODEL);
        classifier = new IntentClassifier(llmRegistry, properties, objectMapper);
    }

    // ==================== classifyStream ====================

    @Test
    @DisplayName("classifyStream: 聚合分片 JSON 并解析意图")
    void classifyStream_aggregatesChunksAndParsesIntent() {
        when(llmRegistry.get(INTENT_MODEL, ChatCapable.class)).thenReturn(capable);
        when(capable.chatStream(any(ChatRequest.class))).thenReturn(
            Flux.<String>just("{\"intent\":", " \"RETRIEVAL\", \"confidence\": 0.9}")
                .map(s -> new StreamChunk(s, null, null, null)));

        IntentResult result = classifier.classifyStream("Spring Boot 自动装配原理").block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo(AgentIntent.RETRIEVAL);
        assertThat(result.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("classifyStream: markdown 包裹的 JSON 被正确抽取")
    void classifyStream_markdownWrappedJsonIsExtracted() {
        when(llmRegistry.get(INTENT_MODEL, ChatCapable.class)).thenReturn(capable);
        when(capable.chatStream(any(ChatRequest.class))).thenReturn(
            Flux.<String>just("```json\n", "{\"intent\":\"GENERAL_TOOL\",\"confidence\":0.8}", "\n```")
                .map(s -> new StreamChunk(s, null, null, null)));

        IntentResult result = classifier.classifyStream("123 * 456 等于多少").block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo(AgentIntent.GENERAL_TOOL);
        assertThat(result.confidence()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("classifyStream: 空 query 立即返回 SAFE_FALLBACK，不触发 LLM 调用")
    void classifyStream_blankQueryReturnsSafeFallbackWithoutLlmCall() {
        IntentResult result = classifier.classifyStream("   ").block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo(AgentIntent.DEEP_RETRIEVAL);
        verifyNoInteractions(llmRegistry);
    }

    @Test
    @DisplayName("classifyStream: 流式持续失败 -> 重试 2 次后降级 DEEP_RETRIEVAL")
    void classifyStream_streamErrorFallsBackAfterRetries() {
        when(llmRegistry.get(INTENT_MODEL, ChatCapable.class)).thenReturn(capable);
        when(capable.chatStream(any(ChatRequest.class)))
            .thenReturn(Flux.error(new RuntimeException("boom")));

        IntentResult result = classifier.classifyStream("q").block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo(AgentIntent.DEEP_RETRIEVAL);
        // 首次 + MAX_RETRIES(2) 次重试 = 3 次 chatStream
        verify(capable, times(3)).chatStream(any(ChatRequest.class));
    }

    // ==================== classify（阻塞对照）====================

    @Test
    @DisplayName("classify: 阻塞路径解析意图（与流式同源对照）")
    void classify_blockingPathParsesIntent() {
        when(llmRegistry.get(INTENT_MODEL, ChatCapable.class)).thenReturn(capable);
        when(capable.chat(any(ChatRequest.class))).thenReturn(
            new LlmResponse("{\"intent\":\"DIRECT_ANSWER\",\"confidence\":0.95}",
                false, null, List.of(), Map.of()));

        IntentResult result = classifier.classify("你好");

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo(AgentIntent.DIRECT_ANSWER);
        assertThat(result.confidence()).isEqualTo(0.95);
    }
}
