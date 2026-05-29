package com.smart.rag.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.event.payload.GuardrailTriggeredPayload;
import com.smart.rag.agent.event.payload.IntentClassifiedPayload;
import com.smart.rag.agent.event.payload.IntermediateAnswerPayload;
import com.smart.rag.agent.event.payload.RetrievalStrategyPayload;
import com.smart.rag.agent.event.payload.SelfReflectionPayload;
import com.smart.rag.agent.event.payload.ToolCalledPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EventPayloadMapper 单元测试。
 * <p>
 * 验证各种 payload 的序列化/反序列化 round-trip，以及 null/空/无效输入的容错行为。
 */
class EventPayloadMapperTest {

    private EventPayloadMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EventPayloadMapper(new ObjectMapper());
    }

    @Nested
    @DisplayName("IntentClassifiedPayload round-trip")
    class IntentClassified {

        @Test
        @DisplayName("序列化 -> 反序列化 round-trip")
        void roundTrip() {
            IntentClassifiedPayload original = new IntentClassifiedPayload("DIRECT_ANSWER", 0.92, "abc123");
            String json = mapper.toJson(original);
            IntentClassifiedPayload restored = mapper.toIntentClassified(json);

            assertThat(restored).isNotNull();
            assertThat(restored.intent()).isEqualTo("DIRECT_ANSWER");
            assertThat(restored.confidence()).isEqualTo(0.92);
            assertThat(restored.rawQueryHash()).isEqualTo("abc123");
        }
    }

    @Nested
    @DisplayName("IntermediateAnswerPayload round-trip")
    class IntermediateAnswer {

        @Test
        @DisplayName("序列化 -> 反序列化 round-trip")
        void roundTrip() {
            IntermediateAnswerPayload original = new IntermediateAnswerPayload(
                    "retrieval", "what is RAG?", "hash456", List.of("doc1", "doc2"));
            String json = mapper.toJson(original);
            IntermediateAnswerPayload restored = mapper.toIntermediateAnswer(json);

            assertThat(restored).isNotNull();
            assertThat(restored.source()).isEqualTo("retrieval");
            assertThat(restored.subQuery()).isEqualTo("what is RAG?");
            assertThat(restored.answerHash()).isEqualTo("hash456");
            assertThat(restored.citedDocIds()).containsExactly("doc1", "doc2");
        }
    }

    @Nested
    @DisplayName("SelfReflectionPayload round-trip")
    class SelfReflection {

        @Test
        @DisplayName("序列化 -> 反序列化 round-trip")
        void roundTrip() {
            SelfReflectionPayload original = new SelfReflectionPayload(0.85, 0.7, "need_more_retrieval");
            String json = mapper.toJson(original);
            SelfReflectionPayload restored = mapper.toSelfReflection(json);

            assertThat(restored).isNotNull();
            assertThat(restored.relevanceScore()).isEqualTo(0.85);
            assertThat(restored.completenessScore()).isEqualTo(0.7);
            assertThat(restored.suggestion()).isEqualTo("need_more_retrieval");
        }
    }

    @Nested
    @DisplayName("RetrievalStrategyPayload round-trip")
    class RetrievalStrategy {

        @Test
        @DisplayName("序列化 -> 反序列化 round-trip")
        void roundTrip() {
            RetrievalStrategyPayload original = new RetrievalStrategyPayload(
                    "hybrid", List.of("q1", "q2", "q3"), 2);
            String json = mapper.toJson(original);
            RetrievalStrategyPayload restored = mapper.toRetrievalStrategy(json);

            assertThat(restored).isNotNull();
            assertThat(restored.strategy()).isEqualTo("hybrid");
            assertThat(restored.subQueries()).containsExactly("q1", "q2", "q3");
            assertThat(restored.targetRound()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("ToolCalledPayload round-trip")
    class ToolCalled {

        @Test
        @DisplayName("序列化 -> 反序列化 round-trip")
        void roundTrip() {
            ToolCalledPayload original = new ToolCalledPayload(
                    3, "hybridSearch", Map.of("query", "test"), true, null, 5, 150L);
            String json = mapper.toJson(original);
            ToolCalledPayload restored = mapper.toToolCalled(json);

            assertThat(restored).isNotNull();
            assertThat(restored.iteration()).isEqualTo(3);
            assertThat(restored.toolName()).isEqualTo("hybridSearch");
            assertThat(restored.success()).isTrue();
            assertThat(restored.resultDocCount()).isEqualTo(5);
            assertThat(restored.durationMs()).isEqualTo(150L);
        }
    }

    @Nested
    @DisplayName("GuardrailTriggeredPayload round-trip")
    class GuardrailTriggered {

        @Test
        @DisplayName("序列化 -> 反序列化 round-trip")
        void roundTrip() {
            GuardrailTriggeredPayload original = new GuardrailTriggeredPayload(
                    "token_budget", "exceeded limit", "stop");
            String json = mapper.toJson(original);
            GuardrailTriggeredPayload restored = mapper.toGuardrailTriggered(json);

            assertThat(restored).isNotNull();
            assertThat(restored.guardrailName()).isEqualTo("token_budget");
            assertThat(restored.reason()).isEqualTo("exceeded limit");
            assertThat(restored.action()).isEqualTo("stop");
        }
    }

    @Nested
    @DisplayName("null / 空 / 无效输入容错")
    class NullEmptyInvalid {

        @Test
        @DisplayName("toIntentClassified(null) 返回 null")
        void nullInput_returnsNull() {
            assertThat(mapper.toIntentClassified(null)).isNull();
        }

        @Test
        @DisplayName("toIntentClassified('') 返回 null")
        void emptyInput_returnsNull() {
            assertThat(mapper.toIntentClassified("")).isNull();
        }

        @Test
        @DisplayName("toIntentClassified('   ') 返回 null (blank)")
        void blankInput_returnsNull() {
            assertThat(mapper.toIntentClassified("   ")).isNull();
        }

        @Test
        @DisplayName("toSelfReflection('invalid json') 返回 null 不抛异常")
        void invalidJson_returnsNull() {
            assertThat(mapper.toSelfReflection("not valid json")).isNull();
        }

        @Test
        @DisplayName("toToolCalled('{') 返回 null 不抛异常")
        void malformedJson_returnsNull() {
            assertThat(mapper.toToolCalled("{")).isNull();
        }

        @Test
        @DisplayName("toGuardrailTriggered('invalid') 返回 null 不抛异常")
        void guardrailInvalidJson_returnsNull() {
            assertThat(mapper.toGuardrailTriggered("invalid")).isNull();
        }

        @Test
        @DisplayName("toIntermediateAnswer(null) 返回 null")
        void intermediateNull_returnsNull() {
            assertThat(mapper.toIntermediateAnswer(null)).isNull();
        }

        @Test
        @DisplayName("toRetrievalStrategy('invalid json') 返回 null")
        void strategyInvalidJson_returnsNull() {
            assertThat(mapper.toRetrievalStrategy("invalid json")).isNull();
        }
    }
}
