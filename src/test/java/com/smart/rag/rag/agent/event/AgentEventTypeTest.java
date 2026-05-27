package com.smart.rag.rag.agent.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentEventType 枚举单元测试。
 * <p>
 * 验证 6 个枚举值存在且 name() 正确，valueOf() 能正确解析，无效字符串抛异常。
 */
class AgentEventTypeTest {

    @Nested
    @DisplayName("枚举值存在性")
    class EnumValues {

        @Test
        @DisplayName("枚举总数为 6")
        void valuesCount() {
            assertThat(AgentEventType.values()).hasSize(6);
        }

        @Test
        @DisplayName("INTENT_CLASSIFIED 存在且 name 正确")
        void intentClassified() {
            assertThat(AgentEventType.INTENT_CLASSIFIED.name()).isEqualTo("INTENT_CLASSIFIED");
        }

        @Test
        @DisplayName("INTERMEDIATE_ANSWER 存在且 name 正确")
        void intermediateAnswer() {
            assertThat(AgentEventType.INTERMEDIATE_ANSWER.name()).isEqualTo("INTERMEDIATE_ANSWER");
        }

        @Test
        @DisplayName("SELF_REFLECTION 存在且 name 正确")
        void selfReflection() {
            assertThat(AgentEventType.SELF_REFLECTION.name()).isEqualTo("SELF_REFLECTION");
        }

        @Test
        @DisplayName("RETRIEVAL_STRATEGY 存在且 name 正确")
        void retrievalStrategy() {
            assertThat(AgentEventType.RETRIEVAL_STRATEGY.name()).isEqualTo("RETRIEVAL_STRATEGY");
        }

        @Test
        @DisplayName("TOOL_CALLED 存在且 name 正确")
        void toolCalled() {
            assertThat(AgentEventType.TOOL_CALLED.name()).isEqualTo("TOOL_CALLED");
        }

        @Test
        @DisplayName("GUARDRAIL_TRIGGERED 存在且 name 正确")
        void guardrailTriggered() {
            assertThat(AgentEventType.GUARDRAIL_TRIGGERED.name()).isEqualTo("GUARDRAIL_TRIGGERED");
        }
    }

    @Nested
    @DisplayName("valueOf 解析")
    class ValueOf {

        @Test
        @DisplayName("valueOf 能正确解析每个枚举值")
        void valueOf_validNames() {
            assertThat(AgentEventType.valueOf("INTENT_CLASSIFIED")).isEqualTo(AgentEventType.INTENT_CLASSIFIED);
            assertThat(AgentEventType.valueOf("INTERMEDIATE_ANSWER")).isEqualTo(AgentEventType.INTERMEDIATE_ANSWER);
            assertThat(AgentEventType.valueOf("SELF_REFLECTION")).isEqualTo(AgentEventType.SELF_REFLECTION);
            assertThat(AgentEventType.valueOf("RETRIEVAL_STRATEGY")).isEqualTo(AgentEventType.RETRIEVAL_STRATEGY);
            assertThat(AgentEventType.valueOf("TOOL_CALLED")).isEqualTo(AgentEventType.TOOL_CALLED);
            assertThat(AgentEventType.valueOf("GUARDRAIL_TRIGGERED")).isEqualTo(AgentEventType.GUARDRAIL_TRIGGERED);
        }

        @Test
        @DisplayName("valueOf 无效字符串抛 IllegalArgumentException")
        void valueOf_invalidString_throwsException() {
            assertThatThrownBy(() -> AgentEventType.valueOf("INVALID_TYPE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("valueOf 空字符串抛 IllegalArgumentException")
        void valueOf_emptyString_throwsException() {
            assertThatThrownBy(() -> AgentEventType.valueOf(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("valueOf 大小写不匹配抛 IllegalArgumentException")
        void valueOf_wrongCase_throwsException() {
            assertThatThrownBy(() -> AgentEventType.valueOf("intent_classified"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
