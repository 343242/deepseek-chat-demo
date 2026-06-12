package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 跨模型 Fallback 降级执行器
 * <p>
 * 按 Fallback Chain 顺序尝试，单个客户端失败后自动降级到下一个。
 * 集成已有的 {@link FallbackEligibility} 过滤用户错误。
 * <p>
 * <b>三层职责严格正交</b>：
 * <pre>
 *   Layer 1 — ResilientClient（单模型弹性）：重试 + 熔断 + 探测，不感知降级
 *   Layer 2 — FallbackExecutor（跨模型编排）：降级链遍历，不感知重试细节
 *   Layer 3 — Caller（业务层）：组装 chain + 调用
 * </pre>
 */
public class FallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackExecutor.class);

    /**
     * 受检异常兼容的函数式接口
     */
    @FunctionalInterface
    public interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private final FallbackEligibility fallbackEligibility;
    @Nullable
    private final Consumer<FallbackEvent> eventPublisher;

    public FallbackExecutor(FallbackEligibility fallbackEligibility) {
        this(fallbackEligibility, null);
    }

    public FallbackExecutor(FallbackEligibility fallbackEligibility,
                            @Nullable Consumer<FallbackEvent> eventPublisher) {
        this.fallbackEligibility = fallbackEligibility;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行 Fallback Chain（阻塞式，责任链语义）
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作
     * @return 第一个成功的结果
     * @throws Exception 所有客户端都失败时抛出 RemoteException(LLM_ALL_MODELS_FAILED)
     */
    public <T extends CapabilityClient, R> R execute(
            List<T> chain,
            CheckedFunction<T, R> action) throws Exception {

        List<T> available = chain.stream()
            .filter(CapabilityClient::isAvailable)
            .toList();
        if (available.isEmpty()) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Fallback chain is empty — all candidates are disabled");
        }

        Exception lastException = null;
        for (int i = 0; i < available.size(); i++) {
            T client = available.get(i);
            try {
                return action.apply(client);
            } catch (Exception e) {
                lastException = e;
                if (!fallbackEligibility.isEligible(e)) {
                    throw e;
                }
                log.warn("Client '{}' failed, trying next: {}",
                    client.candidateId(), e.getMessage());
                if (eventPublisher != null && i + 1 < available.size()) {
                    eventPublisher.accept(new FallbackEvent(
                        client.capability(), client.candidateId(),
                        available.get(i + 1).candidateId(), e));
                }
            }
        }
        throw new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED,
            "所有模型均不可用", lastException);
    }

    /**
     * 执行 Fallback Chain（流式，泛型 {@code <R>}，责任链语义）
     * <p>
     * 切换发生在 {@code onErrorResume} 信号层面。新模型从头开始生成完整响应。
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作，返回 Flux&lt;R&gt;
     * @return 带降级语义的 Flux&lt;R&gt;
     */
    public <T extends CapabilityClient, R> Flux<R> executeStream(
            List<T> chain,
            Function<T, Flux<R>> action) {

        List<T> available = chain.stream()
            .filter(CapabilityClient::isAvailable)
            .toList();
        if (available.isEmpty()) {
            return Flux.error(new RemoteException(
                RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用"));
        }
        return buildStreamChain(available, action);
    }

    /**
     * 构建流式降级链（迭代构造，避免递归 onErrorResume 累积操作符深度）
     */
    private <T extends CapabilityClient, R> Flux<R> buildStreamChain(
            List<T> chain,
            Function<T, Flux<R>> action) {

        Flux<R> result = Flux.error(new RemoteException(
            RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用（流式降级链耗尽）"));

        for (int i = chain.size() - 1; i >= 0; i--) {
            final int index = i;
            final T client = chain.get(i);
            final Flux<R> downstream = result;

            result = Flux.defer(() -> action.apply(client))
                .doOnError(e -> {
                    if (eventPublisher != null && fallbackEligibility.isEligible(e)
                            && index + 1 < chain.size()) {
                        eventPublisher.accept(new FallbackEvent(
                            client.capability(), client.candidateId(),
                            chain.get(index + 1).candidateId(), e));
                    }
                })
                .onErrorResume(e -> {
                    if (!fallbackEligibility.isEligible(e)) {
                        return Flux.error(e);
                    }
                    log.warn("Stream client '{}' failed at index {}, falling back to next: {}",
                        client.candidateId(), index, e.getMessage());
                    return downstream;
                });
        }
        return result;
    }
}
