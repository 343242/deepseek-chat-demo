package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.LlmCapability;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 能力策略注册表 — 自动收集所有 CapabilityStrategy Bean
 * <p>
 * 新增能力只需添加 {@code @Component} 策略实现，
 * 无需修改 Registry、Provider 或 Properties 中的任何 switch。
 */
@Component
public class CapabilityStrategyRegistry {

    private final Map<LlmCapability, CapabilityStrategy> strategies;

    public CapabilityStrategyRegistry(List<CapabilityStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toUnmodifiableMap(
                CapabilityStrategy::capability, Function.identity(),
                (a, b) -> { throw new IllegalStateException(
                    "Duplicate CapabilityStrategy for " + a.capability()
                    + ": " + a.getClass().getName() + " vs " + b.getClass().getName()); }));
    }

    /**
     * 获取指定能力的策略
     *
     * @throws IllegalStateException 当对应能力的策略未注册 ——
     *                               启动期/开发期错误（缺少 {@code @Component} 策略类），
     *                               与同模块的其它 Bean-wiring 错误保持一致
     */
    public CapabilityStrategy get(LlmCapability cap) {
        CapabilityStrategy s = strategies.get(cap);
        if (s == null) {
            throw new IllegalStateException(
                "No CapabilityStrategy registered for " + cap
                + " — ensure corresponding @Component strategy is on classpath");
        }
        return s;
    }

    public boolean hasStrategy(LlmCapability cap) {
        return strategies.containsKey(cap);
    }
}
