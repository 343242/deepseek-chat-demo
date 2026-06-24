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
 * <p>
 * <b>为何不标 {@code @Component}</b>：本类由 {@code LlmClientFactory} 按 candidate 实例化——
 * 每个 candidate 可能对应不同的 {@code probeTimeoutMs} 和 {@code SharedProbeRegistry} 配置
 * （取决于部署模式与监控策略）。Spring 自动扫描无法表达这种"按候选差异化构造"的语义，
 * 因此由工厂层显式 {@code new ProbeHandler(...)} 创建，并通过 {@code LlmClientRegistry}
 * 注入到对应的 {@link com.smart.rag.infrastructure.llm.resilience.ResilientChatClient}。
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
     * 包装 Flux，添加首包超时检测 + 探测去重 + 成功回调。
     * <p>
     * <b>两条探测路径</b>（由 {@link #wrapWithInFlightProbeOrDelegate} 选择）：
     * <ol>
     *   <li>已有同 candidateId 的探测在飞 → 等待其结果，复用结论（跳过重复探测）</li>
     *   <li>无在飞探测 → 委托给 {@link ProbeStreamHandler#wrapWithProbe} 执行首包超时检测</li>
     * </ol>
     * <p>
     * <b>onProbeSuccess 回调注册</b>：两条路径返回的 Flux 都会附加 {@code doOnNext} 回调
     * （仅在 {@code onProbeSuccess} 非 null 时）。无论走哪条路径，只要首包成功到达就会触发回调，
     * 用于 HALF_OPEN → CLOSED 状态转换。{@code onProbeSuccess} 为 null 时跳过注册。
     *
     * @param candidateId    用于日志、熔断记录、探测去重 key
     * @param raw            原始流式响应
     * @param onProbeSuccess 首包到达后的成功回调（用于 HALF_OPEN → CLOSED 状态转换），
     *                       为 null 时不添加回调
     * @return 带首包探测的 Flux
     */
    public <T> Flux<T> wrap(String candidateId, Flux<T> raw,
                              @Nullable Runnable onProbeSuccess) {
        Flux<T> probed = wrapWithInFlightProbeOrDelegate(candidateId, raw);
        if (onProbeSuccess == null) return probed;
        AtomicBoolean notified = new AtomicBoolean(false);
        return probed.doOnNext(first -> {
            if (notified.compareAndSet(false, true)) {
                onProbeSuccess.run();
            }
        });
    }

    /**
     * 选择探测路径：等待在飞探测 / 委托给 ProbeStreamHandler。
     * <p>
     * <ul>
     *   <li>{@code probeRegistry == null} 或 无在飞探测 → 委托 {@code delegate.wrapWithProbe(...)}</li>
     *   <li>有在飞探测 → 等待 {@link CompletableFuture}：
     *     <ul>
     *       <li>成功 → 跳过探测，直接发流（模型已验证可用）</li>
     *       <li>失败 → 转为 {@link ProbeTimeoutException}，由 {@link RetryPolicy#retryStream}
     *           识别为可重试异常继续重试</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    private <T> Flux<T> wrapWithInFlightProbeOrDelegate(String candidateId, Flux<T> raw) {
        if (probeRegistry == null) {
            return delegate.wrapWithProbe(candidateId, raw);
        }
        CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(candidateId);
        if (inFlight == null) {
            return delegate.wrapWithProbe(candidateId, raw);
        }
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
