package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.fallback.cache.HealthEntry;
import com.smart.rag.infrastructure.fallback.cache.ModelHealthCache;
import com.smart.rag.infrastructure.fallback.probe.ProbeResult;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式重试处理器
 * <p>
 * 管理流式场景下的两阶段降级策略：
 * <ol>
 *   <li>阶段一：同模型重试（丢弃部分回复，重发 prompt），最多 maxRetries 次</li>
 *   <li>阶段二：降级切换到下一个备选模型（同样享有阶段一）</li>
 * </ol>
 * <p>
 * 可选集成探测缓存优化：
 * <ul>
 *   <li>策略 A：{@link SharedProbeRegistry} 共享探测去重</li>
 *   <li>策略 B：{@link ModelHealthCache} Redis 健康缓存</li>
 * </ul>
 */
public class StreamRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamRetryHandler.class);

    private final int maxRetries;
    private final FallbackEligibility eligibility;
    @Nullable
    private final ModelHealthCache healthCache;
    @Nullable
    private final SharedProbeRegistry probeRegistry;
    private final int probeTimeoutSeconds;

    public StreamRetryHandler(int maxRetries, FallbackEligibility eligibility) {
        this(maxRetries, eligibility, null, null, 10);
    }

    public StreamRetryHandler(int maxRetries, FallbackEligibility eligibility,
                              @Nullable ModelHealthCache healthCache,
                              @Nullable SharedProbeRegistry probeRegistry,
                              int probeTimeoutSeconds) {
        this.maxRetries = maxRetries;
        this.eligibility = eligibility;
        this.healthCache = healthCache;
        this.probeRegistry = probeRegistry;
        this.probeTimeoutSeconds = probeTimeoutSeconds;
    }

    public Flux<String> execute(List<String> chain, int chainIndex, int retryCount,
                                StreamFactory streamFactory) {
        if (chainIndex >= chain.size()) {
            log.error("All fallback attempts exhausted for stream, tried: {}", chain);
            return Flux.error(new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "所有模型均不可用，请稍后重试（已尝试 " + chain.size() + " 个模型）"));
        }

        String currentModel = chain.get(chainIndex);

        return Flux.defer(() -> {
            // 策略 B：Redis 健康缓存查询
            if (healthCache != null) {
                HealthEntry cached = healthCache.get(currentModel);
                if (cached != null && cached.isHealthy()) {
                    log.debug("Health cache HIT for '{}', skipping probe", currentModel);
                    return doModelStream(currentModel, chain, chainIndex, retryCount,
                            streamFactory, true);
                }
            }

            // 策略 A：共享探测去重
            if (probeRegistry != null) {
                CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(currentModel);
                if (inFlight != null) {
                    log.debug("Sharing in-flight probe for '{}'", currentModel);
                    return awaitSharedProbe(currentModel, chain, chainIndex,
                            retryCount, streamFactory, inFlight);
                }
            }

            return doModelStream(currentModel, chain, chainIndex, retryCount,
                    streamFactory, false);
        });
    }

    /**
     * 等待共享探测结果并路由到对应策略。
     * <p>
     * 提取公共的 {@code Mono.fromFuture(inFlight).timeout().flatMapMany()} 逻辑，
     * 消除 {@link #execute} 和 {@link #doModelStream} 中的重复代码。
     */
    private Flux<String> awaitSharedProbe(String currentModel, List<String> chain,
                                           int chainIndex, int retryCount,
                                           StreamFactory streamFactory,
                                           CompletableFuture<ProbeResult> inFlight) {
        return Mono.fromFuture(() -> inFlight)
                .timeout(Duration.ofSeconds(probeTimeoutSeconds))
                .flatMapMany(result -> {
                    if (result.success()) {
                        if (healthCache != null) {
                            healthCache.putHealthy(currentModel, result.latencyMs());
                        }
                        return doModelStream(currentModel, chain, chainIndex,
                                retryCount, streamFactory, true);
                    }
                    if (healthCache != null) {
                        healthCache.putUnhealthy(currentModel);
                    }
                    return execute(chain, chainIndex + 1, 0, streamFactory);
                })
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn("Shared probe wait timed out for '{}'", currentModel);
                    return execute(chain, chainIndex + 1, 0, streamFactory);
                });
    }

    private Flux<String> doModelStream(String currentModel, List<String> chain,
                                        int chainIndex, int retryCount,
                                        StreamFactory streamFactory, boolean skipProbe) {
        AtomicBoolean emitted = new AtomicBoolean(false);

        CompletableFuture<ProbeResult> myProbe = null;
        long probeStart = 0;

        if (!skipProbe && probeRegistry != null) {
            myProbe = probeRegistry.tryRegister(currentModel);
            if (myProbe == null) {
                CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(currentModel);
                if (inFlight != null) {
                    return awaitSharedProbe(currentModel, chain, chainIndex,
                            retryCount, streamFactory, inFlight);
                }
            } else {
                probeStart = System.currentTimeMillis();
            }
        }

        Flux<String> stream = skipProbe
                ? streamFactory.createDirect(currentModel)
                : streamFactory.create(currentModel);

        final CompletableFuture<ProbeResult> probeFuture = myProbe;
        final long startTime = probeStart;

        return stream
                .doOnNext(item -> {
                    if (emitted.compareAndSet(false, true) && probeFuture != null) {
                        long latency = System.currentTimeMillis() - startTime;
                        probeFuture.complete(ProbeResult.success(currentModel, latency));
                        if (healthCache != null) {
                            healthCache.putHealthy(currentModel, latency);
                        }
                    }
                })
                .doOnComplete(() -> {
                    if (!emitted.get() && probeFuture != null) {
                        probeFuture.complete(ProbeResult.failure(currentModel));
                    }
                })
                .onErrorResume(e -> {
                    if (emitted.get()) {
                        log.warn("Stream failed after emitting data for model '{}'; not retrying or falling back",
                                currentModel);
                        return Flux.error(e);
                    }

                    if (e instanceof ModelCircuitOpenException) {
                        log.warn("Stream model '{}' skipped by circuit breaker, falling back", currentModel);
                        return execute(chain, chainIndex + 1, 0, streamFactory);
                    }

                    if (e instanceof ProbeTimeoutException) {
                        if (probeFuture != null) {
                            probeFuture.complete(ProbeResult.failure(currentModel));
                        }
                        if (healthCache != null) {
                            healthCache.putUnhealthy(currentModel);
                        }
                        return execute(chain, chainIndex + 1, 0, streamFactory);
                    }

                    if (!eligibility.isEligible(e)) {
                        return Flux.error(e);
                    }

                    if (retryCount + 1 < maxRetries) {
                        log.warn("Stream retry {}/{} for model '{}': {}",
                                retryCount + 2, maxRetries, currentModel, e.getMessage());
                        return execute(chain, chainIndex, retryCount + 1, streamFactory);
                    }

                    log.warn("Stream retries exhausted for model '{}' ({} attempts), falling back",
                            currentModel, maxRetries);
                    return execute(chain, chainIndex + 1, 0, streamFactory);
                });
    }

    /**
     * 流式调用工厂 — 将模型 ID 转换为 Flux&lt;String&gt;
     */
    @FunctionalInterface
    public interface StreamFactory {
        Flux<String> create(String modelId);

        /**
         * 创建不带探测包装的直连流（缓存命中时使用）
         */
        default Flux<String> createDirect(String modelId) {
            return create(modelId);
        }
    }
}
