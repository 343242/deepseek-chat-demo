package com.smart.rag.infrastructure.fallback;

import java.util.List;

/**
 * 降级候选链提供者接口（策略模式）
 * <p>
 * 根据请求模型 ID 构建有序的降级候选列表。
 * 不同实现可支持不同的降级策略（静态链、按延迟排序、按成功率排序等）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>OCP — 新增降级策略只需新增实现类，零修改 ChatServiceImpl</li>
 *   <li>DIP — ChatServiceImpl 依赖此接口，不依赖具体策略实现</li>
 * </ul>
 */
@FunctionalInterface
public interface FallbackChainProvider {

    /**
     * 构建降级候选链
     *
     * @param requestedModel 用户原始请求的模型 ID
     * @return 有序候选模型列表，至少包含请求模型本身
     */
    List<String> resolve(String requestedModel);
}
