package com.smart.rag.rag.chunk;

import com.smart.rag.rag.config.DocumentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块策略工厂
 * <p>
 * 根据 YAML 配置（app.document.chunk-strategy）选择对应的 {@link ChunkStrategy}。
 * 新增策略只需实现 ChunkStrategy 并注册为 Spring Bean，工厂自动发现。
 * </p>
 */
@Component
public class ChunkStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(ChunkStrategyFactory.class);

    private final Map<String, ChunkStrategy> strategyMap;
    private final ChunkStrategy defaultStrategy;

    public ChunkStrategyFactory(List<ChunkStrategy> strategies, DocumentProperties properties) {
        this.defaultStrategy = strategies.stream()
                .filter(s -> "parent-child".equals(s.strategyName()))
                .findFirst()
                .orElse(strategies.getFirst());

        Map<String, ChunkStrategy> map = new HashMap<>();
        for (ChunkStrategy strategy : strategies) {
            map.put(strategy.strategyName(), strategy);
        }
        this.strategyMap = map;

        log.info("ChunkStrategyFactory initialized: {} strategies registered, active: {}",
                map.keySet(), properties.getChunkStrategy());
    }

    /**
     * 获取当前配置的策略
     */
    public ChunkStrategy getStrategy(String strategyName) {
        ChunkStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("Unknown chunk strategy '{}', falling back to default: {}",
                    strategyName, defaultStrategy.strategyName());
            return defaultStrategy;
        }
        return strategy;
    }

    /**
     * 获取所有已注册策略名称
     */
    public java.util.Set<String> availableStrategies() {
        return strategyMap.keySet();
    }
}
