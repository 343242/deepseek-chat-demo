package com.smart.rag.rag.etl;

import java.util.Comparator;
import java.util.List;

/**
 * ETL 路由策略工厂
 * <p>
 * 遍历所有注册的 {@link EtlRouteStrategy}，选择第一个 {@code shouldApply=true} 的策略执行。
 * 策略按 {@link EtlRouteStrategy#getOrder()} 升序排列，order 越小优先级越高。
 * <p>
 * OCP：新增策略只需新增实现类 + 注册为 Bean，本类无需修改。
 */
public class EtlRouteStrategyFactory {

    private final List<EtlRouteStrategy> strategies;

    public EtlRouteStrategyFactory(List<EtlRouteStrategy> strategies) {
        // 按 order 升序排列，确保高优先级策略先判定
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(EtlRouteStrategy::getOrder))
                .toList();
    }

    /**
     * 根据候选文档特征选择合适的策略
     *
     * @param candidates 待处理的文档候选列表
     * @return 第一个匹配的策略
     * @throws IllegalStateException 如果没有任何策略匹配（不应发生，StandardStrategy 兜底）
     */
    public EtlRouteStrategy resolve(List<EtlCandidate> candidates) {
        for (EtlRouteStrategy strategy : strategies) {
            if (strategy.shouldApply(candidates)) {
                return strategy;
            }
        }
        throw new IllegalStateException("No ETL route strategy matched for " + candidates.size() + " candidates");
    }
}
