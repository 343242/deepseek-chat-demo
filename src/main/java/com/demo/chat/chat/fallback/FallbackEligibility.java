package com.demo.chat.chat.fallback;

import com.demo.chat.exception.ContentFilteredException;
import org.springframework.stereotype.Component;

/**
 * 异常可降级判定器（单一职责）
 * <p>
 * 判断调用过程中抛出的异常是否适合触发降级。
 * <p>
 * 不可降级的场景（用户侧错误，换模型结果相同）：
 * <ul>
 *   <li>{@link ContentFilteredException} — 用户内容违规</li>
 *   <li>{@link com.demo.chat.exception.BusinessException} — 业务逻辑错误（参数校验等）</li>
 * </ul>
 * <p>
 * 可降级的场景（模型侧故障，换模型可能恢复）：
 * <ul>
 *   <li>{@link com.demo.chat.exception.ModelNotFoundException}</li>
 *   <li>{@link com.demo.chat.exception.ProviderNotFoundException}</li>
 *   <li>网络超时、API 5xx/429 等运行时异常</li>
 * </ul>
 */
@Component
public class FallbackEligibility {

    /**
     * 判断异常是否可触发降级
     *
     * @param e 调用过程中抛出的异常
     * @return true 表示可以尝试下一个备选模型
     */
    public boolean isEligible(Throwable e) {
        return !(e instanceof ContentFilteredException
                || e instanceof com.demo.chat.exception.BusinessException);
    }
}
