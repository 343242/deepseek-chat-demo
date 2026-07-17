package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientFactory;

import java.util.List;

/**
 * 用户级 BYOK（Bring Your Own Key）配置源 SPI。
 * <p>
 * 定义在 infrastructure.llm 包，由持有 DB schema 知识的业务模块（modelconfig）
 * 实现并注入，避免 infrastructure 反向依赖业务包（依赖倒置）。
 * <p>
 * 参照 {@code UserPermissionProvider} 的同款 SPI 模式：接口在基础设施层，
 * 实现在业务层。返回类型 {@link LlmClientFactory.ResolvedCandidate} 为 infrastructure
 * 自有类型，不泄漏业务实体。
 *
 * @see LlmClientRegistry
 */
public interface ByokConfigSource {

    /**
     * 返回指定用户的 BYOK 候选链（含解密 key + 命名空间 candidateId）。
     * <p>
     * 三态语义（design §5.4）：
     * <ul>
     *   <li>无行 → 空 List（fallback yml 系统级 snapshot）</li>
     *   <li>全 disabled → 空 List（实现方可记 warn/counter 区分）</li>
     *   <li>有 enabled → ResolvedCandidate 链</li>
     * </ul>
     *
     * @param userId 用户 ID
     * @param cap    LLM 能力（当前仅 CHAT）
     * @return 候选链；空表示 fallback 到系统级配置
     */
    List<LlmClientFactory.ResolvedCandidate> userChain(Long userId, LlmCapability cap);
}
