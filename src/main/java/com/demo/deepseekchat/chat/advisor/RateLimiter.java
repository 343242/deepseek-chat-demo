package com.demo.deepseekchat.chat.advisor;

/**
 * 限流器接口
 * <p>
 * 解耦具体限流算法（令牌桶、滑动窗口、漏桶等）。
 * 实现类只需关心 tryAcquire 逻辑。
 */
public interface RateLimiter {

    /**
     * 尝试获取一个令牌/许可
     *
     * @param key 限流维度 key（如 conversationId）
     * @return true=放行，false=限流
     */
    boolean tryAcquire(String key);
}
