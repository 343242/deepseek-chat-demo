package com.demo.chat.chat.fallback;

import com.demo.chat.exception.ContentFilteredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 兜底降级链解析器
 * <p>
 * 根据请求的模型 ID 和配置的降级链，构建有序的候选模型列表。
 * 原始请求模型始终排在第一位，其后是配置链中不重复的备选模型。
 * <p>
 * 同时提供异常是否"可降级"的判断：
 * <ul>
 *   <li>内容过滤、参数校验等用户侧错误 → 不降级（换模型结果相同）</li>
 *   <li>模型不存在、厂商不可用、网络超时、API 限流等 → 可降级</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 只负责链的构建和异常判定，不关心调用逻辑</li>
 *   <li>OCP — 新的不可降级异常类型只需在 isFallbackEligible 中追加</li>
 * </ul>
 */
@Component
public class FallbackChainResolver {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainResolver.class);

    private final ChatFallbackProperties properties;

    public FallbackChainResolver(ChatFallbackProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建降级候选链
     * <p>
     * 顺序：[请求模型, 备选1, 备选2, ...]，去重后截断到 maxAttempts。
     *
     * @param requestedModel 用户请求的模型 ID
     * @return 有序候选模型列表，至少包含请求模型本身
     */
    public List<String> resolve(String requestedModel) {
        List<String> chain = new ArrayList<>();
        chain.add(requestedModel);

        for (String candidate : properties.defaultChain()) {
            if (!chain.contains(candidate)) {
                chain.add(candidate);
            }
        }

        int limit = Math.min(chain.size(), properties.maxAttempts());
        List<String> result = chain.subList(0, limit);

        if (log.isDebugEnabled() && result.size() > 1) {
            log.debug("Fallback chain for '{}': {}", requestedModel, result);
        }

        return result;
    }

    /**
     * 判断异常是否可触发降级
     * <p>
     * 不可降级的场景：
     * <ul>
     *   <li>{@link ContentFilteredException} — 用户内容问题，换模型结果相同</li>
     *   <li>{@link IllegalArgumentException} — 请求参数错误，不属于模型侧故障</li>
     * </ul>
     *
     * @param e 调用过程中抛出的异常
     * @return true 表示可以尝试下一个备选模型
     */
    public boolean isFallbackEligible(Throwable e) {
        return !(e instanceof ContentFilteredException
                || e instanceof IllegalArgumentException);
    }
}
