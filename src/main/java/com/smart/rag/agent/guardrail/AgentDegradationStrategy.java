package com.smart.rag.agent.guardrail;

import com.smart.rag.infrastructure.exception.BusinessException;
import com.smart.rag.agent.config.AgentRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 全局降级策略
 * <p>
 * 当 Agent 模式完全不可用时（如 Tool Calling 不支持、意图识别模型不可达），
 * 降级到 MULTI_TURN + RetrievalAugmentationAdvisor（固定 Pipeline RAG）。
 * <p>
 * 降级入口由 {@code ChatAdvisorChainFactory} 调用，
 * 实际降级链路构建复用现有 MULTI_TURN 的构建逻辑。
 */
@Component
public class AgentDegradationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentDegradationStrategy.class);

    private final AgentRagProperties properties;

    public AgentDegradationStrategy(AgentRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 判断是否需要降级
     * <p>
     * 根据异常类型和配置决定是否降级到 MULTI_TURN 模式。
     *
     * @param e 触发降级的异常
     * @return true 表示应该降级到 MULTI_TURN
     */
    public boolean shouldDegrade(Exception e) {
        if (!properties.degradeOnFailure()) {
            log.debug("Degradation disabled by config (degrade-on-failure=false)");
            return false;
        }

        // 不可降级的异常类型 -- 这些异常在 MULTI_TURN 模式下同样会发生
        if (isNonRecoverable(e)) {
            log.debug("Non-recoverable exception, degradation not applicable: {}",
                e.getClass().getSimpleName());
            return false;
        }

        log.warn("Agent degradation triggered by {}: {}",
            e.getClass().getSimpleName(), e.getMessage());
        return true;
    }

    /**
     * 判断异常是否不可恢复（降级也无法解决）
     */
    private boolean isNonRecoverable(Exception e) {
        // 业务异常 -- 统一业务校验失败，降级后同样会失败
        if (e instanceof BusinessException) {
            return true;
        }
        // 参数校验异常 -- 降级后同样会失败
        if (e instanceof IllegalArgumentException) {
            return true;
        }
        // 非法状态异常 -- 通常是编程错误
        if (e instanceof IllegalStateException) {
            return true;
        }
        // 空指针异常 -- 编程错误
        if (e instanceof NullPointerException) {
            return true;
        }
        return false;
    }
}
