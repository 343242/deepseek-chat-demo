package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
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
 * 设计原则：
 * <ul>
 *   <li>SRP — 只负责流式重试和降级的 Flux 编排，不关心 ChatClient 如何构建</li>
 *   <li>递归深度保护 — 已尝试模型不重复，总尝试次数硬上限</li>
 * </ul>
 */
public class StreamRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamRetryHandler.class);

    private final int maxRetries;
    private final FallbackEligibility eligibility;

    /**
     * @param maxRetries   同模型最大重试次数（含首次请求）
     * @param eligibility  异常可降级判定器
     */
    public StreamRetryHandler(int maxRetries, FallbackEligibility eligibility) {
        this.maxRetries = maxRetries;
        this.eligibility = eligibility;
    }

    /**
     * 构建带重试 + 降级的流式 Flux
     * <p>
     * 对每个候选模型：
     * <ol>
     *   <li>尝试调用，失败时同模型重试（最多 maxRetries 次）</li>
     *   <li>重试全部失败后，切换到下一个候选模型</li>
     * </ol>
     * <p>
     * 每次重试/切换都丢弃之前已收集的部分回复，重新发送完整 prompt。
     *
     * @param chain       降级候选链（有序模型 ID 列表）
     * @param chainIndex  当前候选在链中的索引
     * @param retryCount  当前模型已重试次数
     * @param streamFactory  流式调用工厂（接收模型 ID，返回 Flux）
     * @return SSE 文本流
     */
    public Flux<String> execute(List<String> chain, int chainIndex, int retryCount,
                                StreamFactory streamFactory) {
        if (chainIndex >= chain.size()) {
            log.error("All fallback attempts exhausted for stream, tried: {}", chain);
            return Flux.error(new BusinessException(
                    "所有模型均不可用，请稍后重试（已尝试 " + chain.size() + " 个模型）"));
        }

        String currentModel = chain.get(chainIndex);

        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            return streamFactory.create(currentModel)
                    .doOnNext(ignored -> emitted.set(true))
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
                        return execute(chain, chainIndex + 1, 0, streamFactory);
                    }

                    // 不可降级的异常 — 直接传播，不重试不切换
                    if (!eligibility.isEligible(e)) {
                        return Flux.error(e);
                    }

                    // 阶段一：同模型重试
                    if (retryCount + 1 < maxRetries) {
                        log.warn("Stream retry {}/{} for model '{}': {}",
                                retryCount + 2, maxRetries, currentModel, e.getMessage());
                        return execute(chain, chainIndex, retryCount + 1, streamFactory);
                    }

                    // 阶段二：降级切换到下一个候选
                    log.warn("Stream retries exhausted for model '{}' ({} attempts), falling back",
                            currentModel, maxRetries);
                    return execute(chain, chainIndex + 1, 0, streamFactory);
                });
        });
    }

    /**
     * 流式调用工厂 — 将模型 ID 转换为 Flux<String>
     */
    @FunctionalInterface
    public interface StreamFactory {
        Flux<String> create(String modelId);
    }
}
