package com.smart.rag.infrastructure.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 降级链解析器
 * <p>
 * 根据请求的模型 ID 和配置的降级链，构建有序的候选模型列表。
 * 支持模型级粒度：先匹配复合格式，再匹配纯 modelId，最后回退到 default-chain。
 * <p>
 * 算法：
 * <ol>
 *   <li>原始请求模型排在第一位</li>
 *   <li>查找 per-model 降级链，追加其中未出现过的模型</li>
 *   <li>对新追加的每个模型，递归查找其降级链（同样去重）</li>
 *   <li>未命中 per-model 配置时回退到 default-chain</li>
 * </ol>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 只负责链的构建，不关心调用和重试逻辑</li>
 *   <li>环检测 — 通过 Set 去重，防止 A→B→A 无限循环</li>
 * </ul>
 */
@Component
public class FallbackChainResolver implements FallbackChainProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainResolver.class);

    /** 最大总候选数（硬上限，防止配置错误导致超长链） */
    private static final int MAX_CHAIN_SIZE = 15;

    private final ChatFallbackProperties properties;

    public FallbackChainResolver(ChatFallbackProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建降级候选链
     * <p>
     * 顺序：[请求模型, 备选1, 备选2, ...]，去重后截断到 MAX_CHAIN_SIZE。
     * <p>
     * 每个备选模型也会按相同规则展开其降级链，形成递归结构。
     * 已尝试过的模型不再重复，防止无限循环。
     *
     * @param requestedModel 用户请求的模型 ID（支持复合格式和纯 modelId）
     * @return 有序候选模型列表，至少包含请求模型本身
     */
    public List<String> resolve(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return properties.defaultChain();
        }

        Set<String> seen = new LinkedHashSet<>(MAX_CHAIN_SIZE);
        seen.add(requestedModel);

        List<String> toExpand = new ArrayList<>(MAX_CHAIN_SIZE);
        toExpand.add(requestedModel);

        // BFS 展开：对每个模型查找其降级链，追加未出现过的模型
        int cursor = 0;
        while (cursor < toExpand.size() && seen.size() < MAX_CHAIN_SIZE) {
            String current = toExpand.get(cursor);
            List<String> chain = lookupChain(current);

            for (String candidate : chain) {
                if (seen.add(candidate)) {
                    toExpand.add(candidate);
                    if (seen.size() >= MAX_CHAIN_SIZE) {
                        break;
                    }
                } else {
                    log.warn("Circular fallback detected for model '{}': '{}' already in chain, skipping",
                            current, candidate);
                }
            }
            cursor++;
        }

        List<String> result = new ArrayList<>(seen);

        if (log.isDebugEnabled() && result.size() > 1) {
            log.debug("Fallback chain for '{}': {}", requestedModel, result);
        }

        return result;
    }

    /**
     * 查找模型对应的降级链
     * <p>
     * 匹配优先级：复合格式 → 纯 modelId → default-chain
     *
     * @param modelId 模型 ID
     * @return 降级链列表（可能为空）
     */
    private List<String> lookupChain(String modelId) {
        // 1. 精确匹配（通常是复合格式 "deepseek/deepseek-chat"）
        List<String> chain = properties.chains().get(modelId);
        if (chain != null && !chain.isEmpty()) {
            return chain;
        }

        // 2. 纯 modelId 匹配（如果传入的是复合格式，提取 modelId 部分）
        int slashIndex = modelId.indexOf('/');
        if (slashIndex > 0 && slashIndex < modelId.length() - 1) {
            String pureModelId = modelId.substring(slashIndex + 1);
            chain = properties.chains().get(pureModelId);
            if (chain != null && !chain.isEmpty()) {
                return chain;
            }
        }

        // 3. 回退到 default-chain
        return properties.defaultChain();
    }
}
