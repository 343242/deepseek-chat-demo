package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.ProbeStreamHandler;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import com.smart.rag.infrastructure.fallback.probe.ProbeResult;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首包探测处理器 — 包装已有的 {@link ProbeStreamHandler}
 * <p>
 * 复用已有的首包超时检测，额外集成 {@link SharedProbeRegistry} 探测去重：
 * 同一 candidateId 的并发探测共享同一个探测结果，避免重复探测。
 */
public class ProbeHandler {

    private final ProbeStreamHandler delegate;
    @Nullable
    private final SharedProbeRegistry probeRegistry;
    private final long probeTimeoutMs;

    public ProbeHandler(ProbeStreamHandler delegate,
                        @Nullable SharedProbeRegistry probeRegistry,
                        long probeTimeoutMs) {
        this.delegate = delegate;
        this.probeRegistry = probeRegistry;
        this.probeTimeoutMs = probeTimeoutMs;
    }

    /**
     * 包装 Flux，添加首包超时检测 + 探测去重 + 成功回调
     *
     * @param candidateId    用于日志、熔断记录、探测去重 key
     * @param raw            原始流式响应
     * @param onProbeSuccess 首包到达后的成功回调（用于 HALF_OPEN → CLOSED 状态转换），
     *                       为 null 时不添加回调
     * @return 带首包探测的 Flux
     */
    public Flux<String> wrap(String candidateId, Flux<String> raw,
                              @Nullable Runnable onProbeSuccess) {
        if (probeRegistry != null) {
            CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(candidateId);
            if (inFlight != null) {
                return Mono.<ProbeResult>fromFuture(() -> inFlight)
                    .timeout(Duration.ofMillis(probeTimeoutMs))
                    .flatMap(probeResult -> {
                        if (!probeResult.success()) {
                            return Mono.error(new ProbeTimeoutException(
                                "In-flight probe failed for " + candidateId));
                        }
                        return Mono.empty();
                    })
                    .thenMany(Flux.defer(() -> raw));
            }
        }
        Flux<String> probed = delegate.wrapWithProbe(candidateId, raw);

        if (onProbeSuccess != null) {
            AtomicBoolean notified = new AtomicBoolean(false);
            return probed.doOnNext(first -> {
                if (notified.compareAndSet(false, true)) {
                    onProbeSuccess.run();
                }
            });
        }
        return probed;
    }
}
