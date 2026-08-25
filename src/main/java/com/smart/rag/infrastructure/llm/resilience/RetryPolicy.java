package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RateLimitedException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitOpenException;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import com.smart.rag.infrastructure.llm.config.RetryConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 统一重试策略
 * <p>
 * 所有 LLM 操作共用同一套重试参数，由 {@code app.llm.resilience.retry} 配置。
 * <p>
 * <b>可重试 ≠ 可降级</b>——两个正交概念分别判定：
 * <ul>
 *   <li>{@link #isRetryable(Throwable)} — 同模型重试判定：瞬态错误（网络超时、5xx、限流 429、首包超时）</li>
 *   <li>{@code FallbackEligibility.isEligible(Throwable)} — 跨模型降级判定：可重试错误 + 熔断器打开 + 认证失败等</li>
 * </ul>
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double multiplier;

    public RetryPolicy(RetryConfig properties) {
        this.maxAttempts = properties.effectiveMaxAttempts();
        this.baseDelayMs = properties.effectiveBaseDelayMs();
        this.maxDelayMs = properties.effectiveMaxDelayMs();
        this.multiplier = properties.effectiveMultiplier();
    }

    /**
     * 受检异常兼容的函数式接口
     * <p>
     * {@link Supplier} 不允许抛出 checked exception，
     * 而 LLM 调用可能抛出 {@link IOException} 等受检异常。
     */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 带指数退避的同步重试执行器
     * <p>
     * 使用 {@link Thread#sleep(long)} 进行退避等待，会阻塞调用线程。
     * 对于响应式/非阻塞场景，请使用 {@link #retryStream(Supplier)} 代替。
     * <p>
     * 不可重试异常直接抛出；可重试异常重试耗尽后包装为 {@code RemoteException(LLM_TRANSIENT_ERROR)}。
     */
    public <T> T executeWithBackoff(CheckedSupplier<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                lastException = e;
                if (attempt == maxAttempts - 1) {
                    throw new RemoteException(
                        RemoteErrorCode.LLM_TRANSIENT_ERROR,
                        "LLM call failed after " + maxAttempts + " attempts: " + e.getMessage(), e);
                }
                long delay = computeDelay(e, attempt);
                Thread.sleep(delay);
            }
        }
        throw lastException;
    }

    /**
     * 带指数退避的异步重试执行器（流式路径使用）
     * <p>
     * 对 Flux 整体重试（不转换为 Mono，保留流式语义）。
     * 已有数据发送给下游后不再重试（避免内容重复），异常直接传播给
     * {@link FallbackExecutor} 做跨模型降级。
     */
    public <T> Flux<T> retryStream(Supplier<Flux<T>> streamSupplier) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        // 与阻塞路径 executeWithBackoff 同源的退避计算（WS1）：自定义 companion 取代
        // Retry.backoff（Reactor 默认 jitter），保证阻塞/流式退避行为一致。
        Retry companion = Retry.from(signalFlux -> signalFlux.flatMap(context -> {
            Throwable failure = context.failure();
            if (context.totalRetries() < maxAttempts - 1
                    && isRetryable(failure) && !emitted.get()) {
                return Mono.delay(Duration.ofMillis(computeDelay(failure, (int) context.totalRetries())));
            }
            return Mono.error(failure);
        }));
        return Flux.defer(streamSupplier)
            .doOnNext(__ -> emitted.set(true))
            .retryWhen(companion);
    }

    /**
     * 统一退避延迟计算（阻塞/流式同源，WS1）：
     * <ul>
     *   <li>RateLimitedException 携带 Retry-After：服务端指示原样生效——不叠 jitter、
     *       不受 maxDelayMs 约束（决策 15；&gt;60s 的放弃判定在 {@link #isRetryable}）</li>
     *   <li>其余可重试错误：指数退避（cap maxDelayMs）× U(0.5, 1.5) jitter——
     *       对齐 LockRetryExecutor 先例，防多请求同步重试（雷群）</li>
     * </ul>
     */
    long computeDelay(Throwable e, int attempt) {
        if (e instanceof RateLimitedException rle && rle.retryAfterMs() != null) {
            return rle.retryAfterMs();
        }
        long base = Math.min(baseDelayMs * (long) Math.pow(multiplier, attempt), maxDelayMs);
        return (long) (base * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
    }

    /** 空操作重试（不需要重试的场景直接透传） */
    public <T> T executeDirect(CheckedSupplier<T> action) throws Exception {
        return action.get();
    }

    /**
     * 判断异常是否可重试（同模型）
     * <p>
     * 仅瞬态错误可重试——同一模型稍后可能恢复。
     * <b>不可重试</b>：熔断器打开、认证失败、内容过滤、编程错误。
     */
    public boolean isRetryable(Throwable e) {
        if (e instanceof CircuitOpenException) {
            return false;
        }
        if (e instanceof UnsupportedOperationException) {
            return false;
        }
        if (e instanceof RemoteException re) {
            if (re.getErrorCode() == RemoteErrorCode.LLM_RATE_LIMITED) {
                // Retry-After > 60s：服务端明示长时间限流，同模型等待无意义 → 放弃重试直接降级（决策 15）
                return !(e instanceof RateLimitedException rle && rle.shouldAbandonRetry());
            }
            return re.getErrorCode() == RemoteErrorCode.LLM_TRANSIENT_ERROR;
        }
        if (e instanceof IOException
            || e instanceof ProbeTimeoutException
            || e instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (e.getCause() instanceof IOException) {
            return true;
        }
        return false;
    }
}
