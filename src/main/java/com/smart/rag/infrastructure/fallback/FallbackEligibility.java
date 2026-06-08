package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ContentFilteredException;
import com.smart.rag.infrastructure.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * 异常可降级判定器（单一职责）
 * <p>
 * 判断调用过程中抛出的异常是否适合触发降级。
 * <p>
 * <b>不可降级</b>的场景（用户侧 / 服务内部错误，换模型结果相同）：
 * <ul>
 *   <li>{@link ClientException} (A类) — 参数错误、权限不足、内容过滤等</li>
 *   <li>{@link ContentFilteredException} — 用户内容违规（ClientException 子类，单独列出因常见）</li>
 *   <li>{@link ServiceException} (B类) — 业务逻辑错误、数据不存在等</li>
 *   <li>NPE / IAE / ISE — 编程错误，换模型不会修复</li>
 * </ul>
 * <p>
 * <b>可降级</b>的场景（模型侧故障，换模型可能恢复）：
 * <ul>
 *   <li>{@link com.smart.rag.infrastructure.exception.RemoteException} (C类) — 第三方服务超时、熔断、限流等</li>
 *   <li>网络超时、IO 异常等运行时异常</li>
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
        // 用户错误（A类）——不降级
        if (e instanceof ContentFilteredException) {
            return false;
        }
        if (e instanceof ClientException) {
            return false;
        }
        // 服务内部错误（B类）——不降级
        if (e instanceof ServiceException) {
            return false;
        }
        // 编程错误——不降级
        if (e instanceof NullPointerException
                || e instanceof IllegalArgumentException
                || e instanceof IllegalStateException) {
            return false;
        }
        // RemoteException (C类) + 网络异常等——可降级
        return true;
    }
}

