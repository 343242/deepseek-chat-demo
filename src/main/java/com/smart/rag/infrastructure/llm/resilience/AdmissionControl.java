package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 并发准入闸门（design llm-resilience-optimization WS4，P7）— per-candidate，
 * 经 {@link AdmissionControlRegistry} 获取（仅系统候选注册，决策 14）。
 * <p>
 * <b>公平 Semaphore（决策 10）</b>：permit 持有为分钟级，非公平模式的 barging 会使早到
 * 等待者在持续满载下反复 BUSY（饥饿）；公平模式的吞吐损耗在低争用频次下可忽略。
 * <p>
 * <b>release 双标志（决策 17）</b>：许可以 {@link Lease} 形态发放，per-lease CAS
 * 防双释放；acquire 失败路径（BUSY/超时/中断）根本拿不到 Lease → 绝不释放
 * （防"未获取而释放"导致闸门超发）。
 * <p>
 * <b>流式非阻塞轮询（决策 16）</b>：订阅线程先 tryAcquire(0)，未得则短周期轮询，
 * 总预算按流逝时间判定（订阅起算）——全程不占任何调度器阻塞线程，排队时间
 * 全部计入 acquire-timeout 预算。
 * <p>
 * LLM_BUSY 语义：不可同模型重试（{@code RetryPolicy.isRetryable} 不认）、
 * 可跨模型降级（RemoteException → FallbackEligibility 自动通过）。
 */
public class AdmissionControl {

    private static final Logger log = LoggerFactory.getLogger(AdmissionControl.class);
    private static final long POLL_INTERVAL_MS = 50;

    /** 禁用态单例（maxConcurrent=0）：所有 acquire 直通、无指标 */
    public static final AdmissionControl DISABLED = new AdmissionControl();

    private final String candidateId;
    private final Semaphore permits;          // null = 禁用
    private final int maxConcurrent;
    private final long acquireTimeoutMs;
    @Nullable
    private final LlmMetrics metrics;

    AdmissionControl(String candidateId, int maxConcurrent, long acquireTimeoutMs, @Nullable LlmMetrics metrics) {
        this.candidateId = candidateId;
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true); // 公平模式（决策 10）
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.metrics = metrics;
        if (metrics != null) {
            metrics.registerInflightGauge(candidateId,
                () -> maxConcurrent - permits.availablePermits());
        }
    }

    private AdmissionControl() {
        this.candidateId = "<disabled>";
        this.maxConcurrent = 0;
        this.permits = null;
        this.acquireTimeoutMs = 0;
        this.metrics = null;
    }

    /** 供注册表比较整个 ConcurrencyConfig（决策 18） */
    int maxConcurrentForCompare() {
        return maxConcurrent;
    }

    long acquireTimeoutMsForCompare() {
        return acquireTimeoutMs;
    }

    /** 注册表 evict 时移除 inflight gauge（AC10 无僵尸序列） */
    void evictGauge() {
        if (metrics != null) {
            metrics.removeInflightGauge(candidateId);
        }
    }

    public boolean isEnabled() {
        return permits != null;
    }

    public String candidateId() {
        return candidateId;
    }

    /** 已发放的许可句柄：close() 幂等（CAS 防双释放，决策 17） */
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 阻塞 acquire（调用方为业务线程，无调度器盲区问题）。
     * <ul>
     *   <li>acquireTimeoutMs 内获得 → 返回 Lease</li>
     *   <li>超时 → 抛 RemoteException(LLM_BUSY)（记录 busy.rejected）</li>
     *   <li>中断 → 还原中断标志 + 抛 RemoteException(LLM_BUSY, "interrupted")</li>
     * </ul>
     * 两条失败路径均未获得许可（无 Lease → 不释放）。
     */
    public Lease acquireBlocking() {
        if (permits == null) {
            return NOOP_LEASE;
        }
        try {
            if (!permits.tryAcquire(acquireTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return reject("acquire timed out after " + acquireTimeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return reject("interrupted while acquiring permit");
        }
        return new PermitLease();
    }

    private Lease reject(String reason) {
        if (metrics != null) metrics.recordBusyRejected(candidateId);
        return throwBusy(reason);
    }

    private static Lease throwBusy(String reason) {
        throw new RemoteException(RemoteErrorCode.LLM_BUSY,
            "Model concurrency limit reached: " + reason);
    }

    /**
     * 流式准入（决策 6/16）：acquire 完成后才订阅实际流——probe 定时器在 acquire 后
     * 才启动（排队不消耗探测预算）；CANCEL/ERROR/COMPLETE 经 doFinally 释放 permit。
     * acquireMono 自身失败（BUSY/超时）时无 Lease → 不释放。
     */
    public <T> Flux<T> gateStream(Supplier<Flux<T>> streamSupplier) {
        if (permits == null) {
            return Flux.defer(streamSupplier);
        }
        return Flux.defer(() -> acquireMono()
            .flatMapMany(lease -> Flux.defer(streamSupplier)
                .doFinally(signal -> lease.close())));
    }

    /**
     * 非阻塞 acquire（决策 16）：订阅线程 tryAcquire(0) 成功即完成；
     * 否则 50ms 周期轮询，以订阅起算的流逝时间 ≥ acquireTimeoutMs 仍未得 → Mono.error(LLM_BUSY)。
     */
    Mono<Lease> acquireMono() {
        Lease immediate = tryAcquireNow();
        if (immediate != null) {
            return Mono.just(immediate);
        }
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(acquireTimeoutMs).toNanos();
        return Flux.interval(Duration.ofMillis(POLL_INTERVAL_MS))
            .onBackpressureDrop()
            .<Lease>handle((tick, sink) -> {
                Lease l = tryAcquireNow();
                if (l != null) sink.next(l);
            })
            .next()
            .timeout(Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime())))
            .onErrorMap(java.util.concurrent.TimeoutException.class,
                e -> {
                    if (metrics != null) metrics.recordBusyRejected(candidateId);
                    return new RemoteException(RemoteErrorCode.LLM_BUSY,
                        "Model concurrency limit reached: acquire timed out after " + acquireTimeoutMs + "ms");
                });
    }

    private Lease tryAcquireNow() {
        return permits != null && permits.tryAcquire() ? new PermitLease() : null;
    }

    private static final Lease NOOP_LEASE = () -> { };

    private final class PermitLease implements Lease {
        private final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public void close() {
            // CAS 防双释放：重复 close 仅首个生效
            if (released.compareAndSet(false, true)) {
                permits.release();
            } else {
                log.debug("Duplicate permit release ignored for candidate {}", candidateId);
            }
        }
    }
}
