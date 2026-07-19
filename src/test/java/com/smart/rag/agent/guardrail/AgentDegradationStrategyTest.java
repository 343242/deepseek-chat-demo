package com.smart.rag.agent.guardrail;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.agent.config.AgentRagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentDegradationStrategy 单元测试。
 * <p>
 * 验证降级策略：可恢复异常降级，不可恢复异常不降级，配置关闭时不降级。
 */
class AgentDegradationStrategyTest {

    /**
     * 创建 degradeOnFailure=true 的配置
     */
    private static AgentRagProperties enabledConfig() {
        return new AgentRagProperties(
                true,       // enabled
                "gpt-4",    // intentModel
                0.1,        // intentTemperature
                2,          // intentRetries
                5000,       // intentTimeoutMs
                10,         // maxToolIterations
                3,          // maxConsecutiveSameTool
                0.8,        // contextWindowRatio
                10000,      // toolTimeoutMs
                true        // degradeOnFailure
        );
    }

    /**
     * 创建 degradeOnFailure=false 的配置
     */
    private static AgentRagProperties disabledConfig() {
        return new AgentRagProperties(
                true,       // enabled
                "gpt-4",    // intentModel
                0.1,        // intentTemperature
                2,          // intentRetries
                5000,       // intentTimeoutMs
                10,         // maxToolIterations
                3,          // maxConsecutiveSameTool
                0.8,        // contextWindowRatio
                10000,      // toolTimeoutMs
                false       // degradeOnFailure
        );
    }

    @Nested
    @DisplayName("可恢复异常 - 应降级")
    class RecoverableExceptions {

        private final AgentDegradationStrategy strategy = new AgentDegradationStrategy(enabledConfig());

        @Test
        @DisplayName("RuntimeException 应降级")
        void runtimeException_shouldDegrade() {
            assertThat(strategy.shouldDegrade(new RuntimeException("connection timeout"))).isTrue();
        }

        @Test
        @DisplayName("IOException 子类应降级")
        void ioException_shouldDegrade() {
            assertThat(strategy.shouldDegrade(new java.io.IOException("network error"))).isTrue();
        }

        @Test
        @DisplayName("SQLException 应降级")
        void sqlException_shouldDegrade() {
            assertThat(strategy.shouldDegrade(new java.sql.SQLException("db timeout"))).isTrue();
        }
    }

    @Nested
    @DisplayName("不可恢复异常 - 不应降级")
    class NonRecoverableExceptions {

        private final AgentDegradationStrategy strategy = new AgentDegradationStrategy(enabledConfig());

        @Test
        @DisplayName("ClientException 不应降级")
        void clientException_shouldNotDegrade() {
            assertThat(strategy.shouldDegrade(new ClientException(ClientErrorCode.BAD_REQUEST))).isFalse();
        }

        @Test
        @DisplayName("IllegalArgumentException 不应降级")
        void illegalArgument_shouldNotDegrade() {
            assertThat(strategy.shouldDegrade(new IllegalArgumentException("invalid arg"))).isFalse();
        }

        @Test
        @DisplayName("IllegalStateException 不应降级")
        void illegalState_shouldNotDegrade() {
            assertThat(strategy.shouldDegrade(new IllegalStateException("bad state"))).isFalse();
        }

        @Test
        @DisplayName("NullPointerException 不应降级")
        void nullPointer_shouldNotDegrade() {
            assertThat(strategy.shouldDegrade(new NullPointerException("NPE"))).isFalse();
        }
    }

    @Nested
    @DisplayName("配置关闭 - 任何异常都不降级")
    class ConfigDisabled {

        private final AgentDegradationStrategy strategy = new AgentDegradationStrategy(disabledConfig());

        @Test
        @DisplayName("degradeOnFailure=false 时 RuntimeException 不降级")
        void runtimeException_disabled_notDegrade() {
            assertThat(strategy.shouldDegrade(new RuntimeException("error"))).isFalse();
        }

        @Test
        @DisplayName("degradeOnFailure=false 时 IOException 不降级")
        void ioException_disabled_notDegrade() {
            assertThat(strategy.shouldDegrade(new java.io.IOException("error"))).isFalse();
        }
    }
}
