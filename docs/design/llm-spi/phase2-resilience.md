# Phase 2: 统一弹性层

> **来源**: [LLM 调用层统一 SPI 重构方案](../llm-unified-spi-refactoring.md) §7
> **版本**: v1.0
> **日期**: 2026-06-08
> **状态**: 设计方案（待评审）
> **实施阶段**: Phase 1 — 新增文件，不修改现有代码

---

## 本 Phase 范围

| 范围 | 章节 | 说明 |
|------|------|------|
| 设计原则 | §7.1 | 重试/熔断/装饰器模式 |
| RetryPolicy | §7.2 | 统一重试策略（同步+流式） |
| FallbackExecutor | §7.3 | 跨模型降级执行器（阻塞+流式） |
| CircuitBreaker | §7.4 | 熔断器适配器（包装已有基础设施） |
| LlmCircuitBreakerAdapterRegistry | §7.5 | 熔断器注册表 |
| ProbeHandler | §7.6 | 首包探测处理器 |
| Resilient 装饰器 | §7.7 | AbstractResilientClient + 三个类型安全装饰器 |
| CapabilityStrategy | §7.8 | 能力策略（消除 switch 扩展瓶颈） |

> **前置依赖**: [Phase 1: 核心接口与抽象类](phase1-core-interfaces.md) — LlmCapability, ModelCandidate, CapabilityClient, ChatCapable 等接口定义，以及异常体系（ProbeTimeoutException, RemoteErrorCode 等）
>
> **后续依赖**: [Phase 3: Provider + Registry + 配置](phase3-provider-registry.md) 依赖本 Phase 的 Resilient 装饰器和 CapabilityStrategy。

---

## 7. 统一弹性层

### 7.1 设计原则

- **重试策略**：所有 LLM 操作共享全局默认参数配置（YAML 一处定义），按能力类型可选覆盖（`retry-overrides`）
- **熔断保护**：按 `candidateId` 粒度独立熔断（同一个供应商的 chat 和 embedding 互不影响）
- **装饰器模式**：ResilientClient 包装原始 Client，透明施加弹性行为
- **类型安全**：三个 Resilient 装饰器，分别对应三种能力类型，编译期检查

### 7.2 `RetryPolicy` — 统一重试策略

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 统一重试策略
 * <p>
 * 所有 LLM 操作共用同一套重试参数，由 {@code app.llm.resilience.retry} 配置。
 * 替换各调用点散落的 for-loop / 自写指数退避。
 * <p>
 * <b>可重试 ≠ 可降级</b>——两个正交概念分别判定：
 * <ul>
 *   <li>{@link #isRetryable(Throwable)} — 同模型重试判定：瞬态错误（网络超时、5xx、限流 429、首包超时）</li>
 *   <li>{@link #isFallbackEligible(Throwable)} — 跨模型降级判定：可重试错误 + 熔断器打开 + 认证失败等</li>
 * </ul>
 * 语义区别示例：
 * <ul>
 *   <li>认证失败（A 类）→ 不可重试（同一模型重试无意义），但可降级（换模型可能成功）</li>
 *   <li>响应格式错误（B 类）→ 不可重试（同一模型重试无意义），但可降级（换模型可能正常）</li>
 *   <li>熔断器打开 → 不可重试（重试无意义，等待冷却），但可降级（换模型绕过熔断）</li>
 *   <li>内容过滤 → 不可重试（请求本身有问题），不可降级（换模型结果相同）</li>
 * </ul>
 * <p>
 * 配置示例：
 * <pre>
 * app:
 *   llm:
 *     resilience:
 *       retry:
 *         maxAttempts: 3
 *         baseDelayMs: 500
 *         maxDelayMs: 5000
 *         multiplier: 2.0
 * </pre>
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
     * 此接口替代 Supplier 作为重试执行器的参数类型。
     */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 带指数退避的同步重试执行器
     * <p>
     * <b>异常包装策略</b>：
     * <ul>
     *   <li>不可重试异常 → 直接抛出原始异常（如 ContentFilteredException、UnsupportedOperationException）</li>
     *   <li>可重试但重试耗尽 → 包装为 {@code RemoteException(LLM_TRANSIENT_ERROR)} 抛出，
     *       使 {@code FallbackExecutor} 和 {@code CircuitBreaker} 通过 {@code RemoteException} 识别瞬态失败</li>
     * </ul>
     *
     * @param action 可重试的操作（允许抛出 checked exception）
     * @return 操作结果
     * @throws Exception 不可重试异常原样抛出；可重试异常重试耗尽后包装为 RemoteException(LLM_TRANSIENT_ERROR)
     */
    public <T> T executeWithBackoff(CheckedSupplier<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    // 不可重试异常（认证失败、内容过滤等）→ 直接抛出，不包装
                    throw e;
                }
                lastException = e;
                if (attempt == maxAttempts - 1) {
                    // 可重试但重试耗尽 → 包装为 RemoteException(LLM_TRANSIENT_ERROR)，
                    // 使 FallbackExecutor/CircuitBreaker 通过 RemoteException 识别瞬态失败
                    throw new RemoteException(
                        RemoteErrorCode.LLM_TRANSIENT_ERROR,
                        "LLM call failed after " + maxAttempts + " attempts: " + e.getMessage(), e);
                }
                long delay = Math.min(
                    baseDelayMs * (long) Math.pow(multiplier, attempt),
                    maxDelayMs
                );
                Thread.sleep(delay);
            }
        }
        throw lastException; // unreachable but satisfies compiler
    }

    /**
     * 带指数退避的异步重试执行器（流式路径使用）
     * <p>
     * 使用 Flux.retryWhen() 实现流式重试：
     * <ul>
     *   <li>对 Flux 整体重试（不转换为 Mono，保留流式语义）</li>
     *   <li>重试间隔由指数退避控制</li>
     *   <li>仅对可重试异常触发重试，不可重试异常直接向下游传播</li>
     *   <li>已有数据发送给下游后不再重试（避免内容重复），异常直接传播给
     *       {@link FallbackExecutor} 做跨模型降级</li>
     * </ul>
     * <p>
     * <b>emitted 追踪</b>：内部创建 {@code AtomicBoolean}，通过 {@code doOnNext} 自动标记。
     * 调用方无需手动管理 emitted 状态——一旦有数据发送给下游，重试自动停止。
     *
     * @param streamSupplier 返回 Flux 的可重试操作
     * @return 带重试语义的 Flux
     */
    public <T> Flux<T> retryStream(Supplier<Flux<T>> streamSupplier) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(streamSupplier)
            .doOnNext(__ -> emitted.set(true))
            .retryWhen(Retry.backoff(maxAttempts, Duration.ofMillis(baseDelayMs))
                .maxBackoff(Duration.ofMillis(maxDelayMs))
                .filter(e -> isRetryable(e) && !emitted.get())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /**
     * 空操作重试（不需要重试的场景直接透传）
     */
    public <T> T executeDirect(CheckedSupplier<T> action) throws Exception {
        return action.get();
    }

    /**
     * 判断异常是否可重试（同模型）
     * <p>
     * 仅瞬态错误可重试——同一模型稍后可能恢复：
     * <ul>
     *   <li>网络超时 / IOException / SocketTimeoutException</li>
     *   <li>供应商 5xx / 网关超时 → 重试耗尽后包装为 {@code RemoteException(LLM_TRANSIENT_ERROR)}</li>
     *   <li>限流 429 → {@code RemoteException(LLM_RATE_LIMITED)}</li>
     *   <li>首包探测超时 → {@link ProbeTimeoutException}</li>
     * </ul>
     * <b>不可重试</b>（即使同一模型重试也无意义）：
     * <ul>
     *   <li>熔断器打开 → {@link ModelCircuitOpenException}（等待冷却，非重试可解）</li>
     *   <li>认证失败 → A/B 类异常（请求本身有问题）</li>
     *   <li>内容过滤 → 请求本身违规（换时间重试结果相同）</li>
     *   <li>编程错误 → {@link UnsupportedOperationException}（如对非流式模型调用 chatStream）</li>
     * </ul>
     */
    private boolean isRetryable(Throwable e) {
        if (e instanceof ModelCircuitOpenException) {
            return false;
        }
        if (e instanceof UnsupportedOperationException) {
            return false; // 编程错误：调用方应在调用前检查 supportsStreaming()
        }
        // C 类瞬态错误可重试（网络超时、5xx、探测超时等）
        // RemoteException(LLM_RATE_LIMITED) 通过错误码识别为可重试（供应商 429 限流，
        // 同一模型稍后可能恢复）——保持 RemoteException 原始类型，不改变异常体系
        // 注意：降级判定由 FallbackExecutor 独立负责，RetryPolicy 不感知降级语义
        if (e instanceof RemoteException re) {
            return re.getErrorCode() == RemoteErrorCode.LLM_RATE_LIMITED;
        }
        return e instanceof IOException
            || e instanceof ProbeTimeoutException;
    }

}
```

### 7.3 `FallbackExecutor` — 跨模型降级执行器

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 跨模型 Fallback 降级执行器
 * <p>
 * 按 Fallback Chain 顺序尝试，单个客户端失败后自动降级到下一个。
 * 集成已有的 {@link FallbackEligibility} 过滤用户错误（不可降级的异常直接终止）。
 * <p>
 * <b>三层职责严格正交</b>：
 * <pre>
 *   Layer 1 — ResilientClient（单模型弹性）：重试 + 熔断 + 探测，不感知降级
 *   Layer 2 — FallbackExecutor（跨模型编排）：降级链遍历，不感知重试细节
 *   Layer 3 — Caller（业务层）：组装 chain + 调用
 * </pre>
 * <b>责任链语义</b>：Fallback Chain 本质上是一条责任链——每个节点（ResilientClient）
 * 独立决定"成功"或"失败"，失败时传递给下一个节点。阻塞式 {@code execute()} 使用
 * for-loop 遍历（等价于 Chain of Responsibility），流式 {@code executeStream()} 使用
 * 迭代构造 {@code Flux.defer() + onErrorResume} 实现惰性责任链（从链尾向链首构建，
 * 每个节点是平坦的 defer+onErrorResume，不累积操作符深度）。
 * <b>降级事件（Observer 模式）</b>：每次降级切换时发布 {@link FallbackEvent}，
 * 供 UI 层提示用户、metrics 采集、日志追踪消费。调用方可通过
 * {@code Flux.doOnNext(event -> ...)} 或 Spring EventListener 订阅。
 * <p>
 * <b>关键约束</b>：传入 {@code action} 的 client 必须是已包装 Resilience 的客户端。
 * {@code fallbackEligibility.isEligible(e)} 是降级的唯一判定入口，
 * 与 {@code RetryPolicy.isRetryable(e)} 完全独立，不共享判定逻辑。
 * 异常分类规则详见 [Phase 1 §11.3](phase1-core-interfaces.md)（A/B/C 三类异常 → 可降级性判定）。
 * Registry 返回的 Fallback Chain 已包含 Resilient 包装。
 */
public class FallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackExecutor.class);

    /**
     * 受检异常兼容的函数式接口
     * <p>
     * {@link Function} 不允许抛出 checked exception，
     * 而 LLM 调用的 {@code chat()} / {@code chatStream()} 可能抛出
     * {@link IOException} 等受检异常。
     * 此接口替代 {@code Function} 作为 {@code execute()} 的参数类型。
     */
    @FunctionalInterface
    public interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private final FallbackEligibility fallbackEligibility;
    /** 降级事件发布器（Observer 模式），可选 */
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
     * @throws RemoteException 所有客户端都失败时抛出（RemoteErrorCode.LLM_ALL_MODELS_FAILED）
     */
    public <T extends CapabilityClient, R> R execute(
            List<T> chain,
            CheckedFunction<T, R> action) throws Exception {

        // 入口预过滤：跳过全部已禁用的候选，避免误报 LLM_ALL_MODELS_FAILED
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
                    // 用户错误（ContentFilteredException 等）不降级，直接抛出
                    throw e;
                }
                log.warn("Client '{}' failed, trying next: {}",
                    client.candidateId(), e.getMessage());
                // 发布降级事件（Observer 模式）
                if (eventPublisher != null && i + 1 < available.size()) {
                    eventPublisher.accept(new FallbackEvent(
                        client.capability(), client.candidateId(),
                        available.get(i + 1).candidateId(), e));
                }
            }
        }
        throw new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用", lastException);
    }

    /**
     * 执行 Fallback Chain（流式，泛型 {@code <R>}，责任链语义）
     * <p>
     * 泛型 {@code R} 使本方法不限于 {@code Flux<String>}——未来流式响应可携带
     * 结构化数据（如 {@code Flux<LlmStreamChunk>} 含 token 计数、finish reason），
     * 无需修改 FallbackExecutor（OCP 合规）。
     * <p>
     * <b>调用层次（弹性层与编排层职责正交）</b>：
     * <pre>
     *   FallbackExecutor.executeStream(chain, c -> c.chatStream(req))
     *     │
     *     ├─ 订阅 client_0 的 chatStream()
     *     │    │  ← client_0 是 ResilientChatClient，内部已编排：
     *     │    │     circuitBreaker → retryPolicy → probeHandler → delegate
     *     │    │  ← FallbackExecutor 不知道也不关心这个内部结构
     *     │    │
     *     │    ├─ 流正常 → 直接输出，不尝试下一个 client
     *     │    └─ 流报错（ResilientClient 内部重试+熔断均耗尽后抛出最终异常）
     *     │         → FallbackExecutor 只看到一个不可恢复的异常，切换到 client_1
     *     │
     *     └─ 所有 client 均报错 → Flux.error(RemoteException.LLM_ALL_MODELS_FAILED)
     * </pre>
     * <b>流式降级语义：从头开始用新模型重新生成</b>
     * <p>
     * 切换发生在 {@code onErrorResume} 信号层面。当模型 A 在输出部分内容后失败：
     * <ul>
     *   <li>已发送给下游的数据片段（SSE chunks）不会回滚——这是 Flux 信号模型的固有特性</li>
     *   <li>新模型 B 从头开始生成完整响应，不续接 A 的不完整片段</li>
     * </ul>
     * <p>
     * <b>⚠ 调用方须知</b>：若模型 A 已发出 N 个 token 后失败，调用方收到的将是
     * 「A 的不完整片段 + B 的完整输出」的拼接，内容可能不连贯。
     * 调用方应在 UI 层提示用户"因模型切换，前文可能不完整"——
     * 通过 {@link FallbackEvent} 可获知降级发生的具体模型和异常原因。
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作，返回 Flux&lt;R&gt;。
     *               FallbackExecutor 不感知 action 内部结构。
     * @return 带降级语义的 Flux&lt;R&gt;
     */
    public <T extends CapabilityClient, R> Flux<R> executeStream(
            List<T> chain,
            Function<T, Flux<R>> action) {

        // 预过滤不可用客户端
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
     * 构建流式降级链（迭代构造，避免递归 onErrorResume 在长链下累积操作符深度）
     * <p>
     * 从链尾向链首迭代构建：每个 {@code Flux.defer()} 仅在前一个 Flux 失败时才订阅下一个，
     * 保持惰性语义。与递归 {@code onErrorResume} 的区别：
     * <ul>
     *   <li>递归：每个降级层嵌套一层 {@code onErrorResume}，链深 = 降级层数</li>
     *   <li>迭代：每个降级层是一个 {@code defer + onErrorResume} 平坦节点，
     *       总操作符深度固定为 2（不随链长增长）</li>
     * </ul>
     */
    private <T extends CapabilityClient, R> Flux<R> buildStreamChain(
            List<T> chain,
            Function<T, Flux<R>> action) {

        // 从链尾向链首迭代构建，最终 result 是链首节点
        Flux<R> result = Flux.error(new RemoteException(
            RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用（流式降级链耗尽）"));

        for (int i = chain.size() - 1; i >= 0; i--) {
            final int index = i;
            final T client = chain.get(i);
            final Flux<R> downstream = result;

            result = Flux.defer(() -> action.apply(client))
                .doOnError(e -> {
                    // 发布降级事件（Observer 模式），供 UI/metrics/logging 消费
                    if (eventPublisher != null && fallbackEligibility.isEligible(e)
                            && index + 1 < chain.size()) {
                        eventPublisher.accept(new FallbackEvent(
                            client.capability(), client.candidateId(),
                            chain.get(index + 1).candidateId(), e));
                    }
                })
                .onErrorResume(e -> {
                    // 用户错误不降级，直接向下游传播
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
```

#### `FallbackEvent` — 降级事件（Observer 模式）

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.LlmCapability;

/**
 * 降级事件 — 记录一次模型切换
 * <p>
 * 发布时机：阻塞式 {@code execute()} 和流式 {@code executeStream()} 在切换到下一个模型时发布。
 * 消费方：
 * <ul>
 *   <li>UI 层：提示用户"因模型切换，前文可能不完整"</li>
 *   <li>Metrics：采集 {@code llm.fallback.invocations} 计数器（标签：capability, from, to）</li>
 *   <li>日志：记录降级链路追踪</li>
 * </ul>
 */
public record FallbackEvent(
    /** 发生降级的能力类型 */
    LlmCapability capability,
    /** 失败的模型 candidateId */
    String fromCandidateId,
    /** 降级目标模型 candidateId */
    String toCandidateId,
    /** 触发降级的异常 */
    Exception cause
) {}
```


### 7.4 `CircuitBreaker` — 熔断器适配器（包装已有基础设施）

> **设计决策**：不重写三态熔断器，而是**包装已有的 `ModelCircuitBreakerRegistry`**。
> 已有实现已包含完整状态机（synchronized + Clock 注入 + halfOpenMaxProbes + releaseProbe），
> 新增 `execute()` / `executeStream()` 高层方法供 Resilient 装饰器使用。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 熔断器适配器 — 包装已有的 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 复用已有三态熔断器实现（CLOSED → OPEN → HALF_OPEN），
 * 新增 {@code execute()} / {@code executeStream()} 高层方法，
 * 供 Resilient 装饰器统一调用。
 * <p>
 * <b>异常过滤</b>：仅将基础设施异常（{@link FallbackEligibility#isEligible} 返回 true）
 * 计为熔断失败。用户错误（ContentFilteredException 等）不触发熔断计数，
 * 避免因客户端问题误开熔断器。异常分类详见 [Phase 1 §11.3](phase1-core-interfaces.md)。
 * <p>
 * <b>异常类型</b>：{@link ModelCircuitOpenException} 和 {@link ProbeTimeoutException}
 * 均为 {@link RemoteException} 子类（详见 [Phase 1 §11.4](phase1-core-interfaces.md)），
 * 保留独立类名用于精确 {@code instanceof} 检查。
 * <p>
 * <b>流超时语义</b>：{@code executeStream()} 仅检测首包超时（与已有 {@code ProbeStreamHandler} 一致），
 * 首包到达后不再限制流的总时长。全流超时由客户端断开连接自然触发。
 */
public class CircuitBreaker {

    private final ModelCircuitBreakerRegistry registry;
    private final FallbackEligibility fallbackEligibility;
    private final String candidateId;

    public CircuitBreaker(ModelCircuitBreakerRegistry registry,
                          FallbackEligibility fallbackEligibility,
                          String candidateId) {
        this.registry = registry;
        this.fallbackEligibility = fallbackEligibility;
        this.candidateId = candidateId;
    }

    /**
     * 阻塞式执行（带熔断保护）
     * <p>
     * OPEN → 抛出 {@link ModelCircuitOpenException}（已有异常类型）
     * HALF_OPEN → 放行（由已有 halfOpenMaxProbes 控制并发数）
     */
    public <T> T execute(RetryPolicy.CheckedSupplier<T> action) throws Exception {
        if (!registry.isCallAllowed(candidateId)) {
            throw new ModelCircuitOpenException(candidateId);
        }
        try {
            T result = action.get();
            registry.recordSuccess(candidateId);
            return result;
        } catch (Exception e) {
            // 仅基础设施异常计为熔断失败
            if (fallbackEligibility.isEligible(e)) {
                registry.recordFailure(candidateId);
            }
            throw e;
        }
    }

    /**
     * 流式执行（带熔断保护）
     * <p>
     * 订阅时检查状态，流结束时记录成功，异常时按可降级性记录失败。
     * HALF_OPEN 下的探测流由已有 {@code releaseProbe()} 管理。
     * <p>
     * <b>首包超时</b>：由 {@link ProbeHandler} 在 {@code ResilientChatClient} 中施加，
     * 本方法不做首包超时检测（避免双层超时冲突）。ProbeHandler 超时抛出
     * {@link ProbeTimeoutException}，由外层 {@link RetryPolicy#retryStream} 识别为可重试异常。
     */
    public <T> Flux<T> executeStream(Supplier<Flux<T>> streamSupplier) {
        if (!registry.isCallAllowed(candidateId)) {
            return Flux.error(new ModelCircuitOpenException(candidateId));
        }
        return Flux.defer(streamSupplier)
            .doOnComplete(() -> {
                registry.recordSuccess(candidateId);
                registry.releaseProbe(candidateId);
            })
            .doOnError(e -> {
                // 仅基础设施异常计为熔断失败。
                // 排除 ProbeTimeoutException：ProbeStreamHandler 已调用 breakers.recordFailure。
                if (!(e instanceof ProbeTimeoutException) && isInfraFailure(e)) {
                    registry.recordFailure(candidateId);
                }
                registry.releaseProbe(candidateId);
            })
            .doOnCancel(() -> {
                // 客户端主动断开不算失败
                registry.releaseProbe(candidateId);
            });
    }

    /**
     * 判断异常是否为基础设施异常（可触发熔断计数）
     * <p>
     * 命名为 {@code isInfraFailure} 而非 {@code isRetryable}——避免与
     * {@link RetryPolicy#isRetryable(Throwable)} 同名异义的维护混淆。
     * RetryPolicy.isRetryable 判定"是否值得同模型重试"，
     * 本方法判定"是否计入熔断器失败计数"——语义不同，命名应体现差异。
     * <p>
     * 排除 {@link ProbeTimeoutException}（已由 ProbeStreamHandler 处理）。
     */
    private boolean isInfraFailure(Throwable e) {
        if (e instanceof ProbeTimeoutException) {
            return false;
        }
        return fallbackEligibility.isEligible(e);
    }

    /** 当前状态（委托给已有实现） */
    public CircuitBreakerState getState() {
        return registry.stateOf(candidateId);
    }

    /**
     * 探测成功回调 — 仅在 HALF_OPEN 状态下记录成功（触发 HALF_OPEN → CLOSED 转换）。
     * <p>
     * 在 CLOSED 状态下为空操作（no-op），不影响熔断计数。
     * 由 {@link ProbeHandler#wrap} 在首包到达时通过 {@code onProbeSuccess} 回调调用。
     * <p>
     * <b>与 {@code doOnComplete → recordSuccess} 的关系</b>：
     * <ul>
     *   <li>{@code recordProbeSuccess} 在首包到达时触发，用于快速恢复 HALF_OPEN</li>
     *   <li>{@code doOnComplete → recordSuccess} 在流正常结束时触发，用于 CLOSED 状态的持续健康记录</li>
     *   <li>两者在 HALF_OPEN 下可能重复调用 {@code registry.recordSuccess()}，
     *       但已有 {@code ModelCircuitBreakerRegistry.recordSuccess()} 是幂等的（仅改变状态，不累加计数）</li>
     * </ul>
     */
    public void recordProbeSuccess() {
        if (registry.stateOf(candidateId) == CircuitBreakerState.HALF_OPEN) {
            registry.recordSuccess(candidateId);
            log.info("Circuit breaker for '{}' recovered: HALF_OPEN → CLOSED (probe success)", candidateId);
        }
    }
}
```

### 7.5 `LlmCircuitBreakerAdapterRegistry` — 熔断器注册表（包装已有）

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 candidateId 管理的熔断器注册表 — 包装已有 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 不创建新的熔断器实例，而是为每个 candidateId 创建 {@link CircuitBreaker} 适配器，
 * 底层委托给已有的 {@link ModelCircuitBreakerRegistry}。
 */
@Component
public class LlmCircuitBreakerAdapterRegistry {

    private final ModelCircuitBreakerRegistry delegate;
    private final FallbackEligibility fallbackEligibility;
    private final ConcurrentHashMap<String, CircuitBreaker> adapters = new ConcurrentHashMap<>();

    public LlmCircuitBreakerAdapterRegistry(ModelCircuitBreakerRegistry delegate,
                                            FallbackEligibility fallbackEligibility) {
        this.delegate = delegate;
        this.fallbackEligibility = fallbackEligibility;
    }

    /**
     * 获取或创建指定 candidateId 的熔断器适配器
     */
    public CircuitBreaker getOrCreate(String candidateId) {
        return adapters.computeIfAbsent(candidateId,
            id -> new CircuitBreaker(delegate, fallbackEligibility, id));
    }
}
```


### 7.6 `ProbeHandler` — 首包探测处理器（包装已有基础设施）

> **设计决策**：不重写首包探测，**包装已有的 `ProbeStreamHandler`**。
> 已有实现使用 `Flux.create` + `AtomicBoolean` + 手动 timer 精确控制首包超时，
> 并在超时时调用 `breakers.recordFailure()` 更新熔断计数。
> 新架构复用此实现，额外集成 `SharedProbeRegistry` 探测去重。
>
> **异常类型**：首包超时抛出 {@link ProbeTimeoutException}（[Phase 1 §11.4](phase1-core-interfaces.md)），
> 该异常为 {@link RemoteException} 子类，由 {@link RetryPolicy#retryStream}（§7.2）识别为可重试异常。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.ProbeStreamHandler;
import com.smart.rag.infrastructure.fallback.probe.ProbeResult;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;
import reactor.core.publisher.Mono;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首包探测处理器 — 包装已有的 {@link ProbeStreamHandler}
 * <p>
 * 复用已有的首包超时检测（Flux.create + timer + breakers.recordFailure），
 * 额外集成 {@link SharedProbeRegistry} 探测去重：
 * <ul>
 *   <li>同一 candidateId 的并发探测共享同一个探测结果，避免重复探测</li>
 * </ul>
 * <p>
 * <b>探测成功路径（HALF_OPEN → CLOSED 恢复）</b>：
 * <ul>
 *   <li>首包到达 → 已有 ProbeStreamHandler 内部取消超时 timer → 返回带首包的 Flux</li>
 *   <li>ProbeHandler 在 {@code doOnNext(first)} 中调用 {@code onProbeSuccess} 回调</li>
 *   <li>回调委托给 {@link CircuitBreaker#recordProbeSuccess()} —— 仅在 HALF_OPEN 状态下
 *       调用底层 {@code registry.recordSuccess(candidateId)}，将熔断器转回 CLOSED</li>
 *   <li>正常 CLOSED 状态下的调用为空操作（no-op），不影响熔断计数</li>
 * </ul>
 * <p>
 * <b>探测失败路径</b>：
 * <ul>
 *   <li>首包超时 → ProbeStreamHandler 调用 breakers.recordFailure() + 抛出 ProbeTimeoutException</li>
 *   <li>ProbeTimeoutException 被 {@link RetryPolicy#retryStream} 识别为可重试异常</li>
 *   <li>重试耗尽 → 异常冒泡到 {@link CircuitBreaker#executeStream} 的 doOnError → recordFailure</li>
 *   <li>由 {@link FallbackExecutor#executeStream} 降级到下一个模型</li>
 * </ul>
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
     * <p>
     * 探测去重语义：
     * <ul>
     *   <li>已有探测成功 → 跳过探测，直接发流（模型已验证可用，无需重复探测）</li>
     *   <li>已有探测失败 → 跳过探测，直接发流（探测失败可能是并发条件导致，
     *       当前请求让下游 retryStream 处理，而非立即报错）</li>
     *   <li>无在飞探测 → 正常委托给 ProbeStreamHandler 进行首包探测</li>
     * </ul>
     *
     * @param candidateId    用于日志、熔断记录、探测去重 key
     * @param raw            原始流式响应
     * @param onProbeSuccess 首包到达后的成功回调（用于 HALF_OPEN → CLOSED 状态转换），
     *                       为 null 时不添加回调（非 CHAT 能力无探测场景）
     * @return 带首包探测的 Flux
     */
    public Flux<String> wrap(String candidateId, Flux<String> raw,
                              @Nullable Runnable onProbeSuccess) {
        // 探测去重：如果已有同模型的探测在飞，等待其结果后跳过探测
        if (probeRegistry != null) {
            CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(candidateId);
            if (inFlight != null) {
                // 无论已有探测成功还是失败，都直接发流。
                // 成功说明模型可用，失败可能是并发条件导致，让 retryStream 处理。
                return Mono.fromFuture(() -> inFlight)
                    .timeout(Duration.ofMillis(probeTimeoutMs))
                    .onErrorResume(e -> Mono.empty()) // 探测等待超时也继续发流
                    .thenMany(raw);
            }
        }
        // 委托给已有的 ProbeStreamHandler（内部已集成 breakers.recordFailure）
        Flux<String> probed = delegate.wrapWithProbe(candidateId, raw);

        // 首包到达时触发成功回调（HALF_OPEN → CLOSED 状态转换）
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
```



### 7.7 Resilient 装饰器

> **设计决策**：
> 1. 装饰器**只实现能力接口**（[ChatCapable](phase1-core-interfaces.md#51-chatcapable--chat-能力契约) / [EmbeddingCapable](phase1-core-interfaces.md#52-embeddingcapable--embedding-能力契约) / [RerankCapable](phase1-core-interfaces.md#53-rerankcapable--rerank-能力契约)），不继承被装饰者的抽象类。
>    调用方通过接口交互，无法区分原始客户端和弹性包装——这正是装饰器的透明性。
> 2. 三个 Resilient 装饰器共享 `AbstractResilientClient<T>` 基类，消除 `CapabilityClient` 委托的 DRY 违反。
> 3. **`ResilientChatClient` 同时实现 `ChatCapable` 和 `ToolCallingCapable`**（[Phase 1 §5.4](phase1-core-interfaces.md#54-toolcallingcapable--工具调用能力isp-拆分)）。
>    工具调用已是主流大模型标配能力，`chatWithTools()` 与 `chat()` 走统一的弹性保护路径（重试 + 熔断）。
>    当底层 delegate 不支持工具调用时，`chatWithTools()` 抛出 `UnsupportedOperationException`——
>    这是能力缺失的正确语义，而非 LSP 违反。
> 4. Spring AI `ChatModel` 桥接代码集中在 `ChatModelAdapter`（[Phase 1 §5.5](phase1-core-interfaces.md#55-chatmodeladapter--spring-ai-chatmodel-适配器)），Resilient 装饰器不感知 Spring AI。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AbstractResilientClient — 弹性装饰器公共基类
 * <p>
 * 消除三个 Resilient 装饰器中 {@code CapabilityClient} 委托的 DRY 违反。
 * 泛型 {@code <T>} 约束为 {@code CapabilityClient} 的子接口（如 {@code ChatCapable}），
 * 子类通过 {@code extends AbstractResilientClient<ChatCapable>} 获得统一的委托实现。
 */
abstract class AbstractResilientClient<T extends CapabilityClient> implements CapabilityClient {

    protected final T delegate;
    protected final CircuitBreaker circuitBreaker;
    protected final RetryPolicy retryPolicy;

    protected AbstractResilientClient(T delegate, CircuitBreaker circuitBreaker, RetryPolicy retryPolicy) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retryPolicy = retryPolicy;
    }

    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelName() { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }

    @Override public void close() { delegate.close(); }
}

/**
 * ResilientChatClient — Chat 能力的弹性装饰器（实现 ChatCapable + ToolCallingCapable）
 * <p>
 * 工具调用已是主流大模型标配能力，chatWithTools() 与 chat() 共享同一弹性保护路径。
 * 当底层 delegate 不支持 {@code ToolCallingCapable} 时，{@code chatWithTools()} 抛出
 * {@code UnsupportedOperationException}——这是能力缺失的正确语义。
 * <p>
 * 重试/熔断保护由 {@code AbstractResilientClient} 统一提供，chat 和 chatWithTools 两条路径
 * 使用同一 {@code CircuitBreaker} + {@code RetryPolicy} 实例。
 * <p>
 * 策略矩阵：
 * <pre>
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ chat         │ 指数退避重试   │ ✓            │ ✗（阻塞式）   │
 * │ chatStream   │ 指数退避重试   │ ✓            │ ✓ ProbeHandler│
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 * </pre>
 */
public class ResilientChatClient extends AbstractResilientClient<ChatCapable>
        implements ChatCapable, ToolCallingCapable {

    private final ProbeHandler probeHandler;

    public ResilientChatClient(ChatCapable delegate,
                                CircuitBreaker circuitBreaker,
                                RetryPolicy retryPolicy,
                                ProbeHandler probeHandler) {
        super(delegate, circuitBreaker, retryPolicy);
        this.probeHandler = probeHandler;
    }

    /** 底层 delegate 是否支持工具调用（Registry 层用于过滤） */
    public boolean delegateSupportsToolCalling() {
        return delegate instanceof ToolCallingCapable;
    }

    // ======== Chat 操作（带弹性包装） ========

    @Override
    public LlmResponse chat(ChatRequest request) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.chat(request)
            )
        );
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        // emitted 追踪由 retryStream 内部管理：一旦有数据发送给下游，重试自动停止，
        // 异常直接传播给 FallbackExecutor 做跨模型降级。
        return circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                Flux<String> raw = delegate.chatStream(request);
                return probeHandler != null
                    ? probeHandler.wrap(candidateId(), raw, circuitBreaker::recordProbeSuccess)
                    : raw;
            })
        );
    }

    // ======== Tool Calling 操作（统一弹性路径，不绕过 Resilience） ========

    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        if (!(delegate instanceof ToolCallingCapable tc)) {
            throw new UnsupportedOperationException(
                "Delegate '" + candidateId() + "' does not support tool calling");
        }
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                tc.chatWithTools(request, tools)
            )
        );
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }
}
```

> **工具调用统一弹性路径**：工具调用已是现代大模型的标配能力，`ResilientChatClient`
> 同时实现 `ChatCapable` 和 `ToolCallingCapable`，`chat()` 与 `chatWithTools()`
> 享有完全相同的弹性保护（重试/熔断）。不再存在绕过 Resilience 的独立路径。
> Registry 的 `getToolCallingClient()` 返回 `ResilientChatClient` 本身（非原始 delegate），
> 调用方无需感知装饰器存在。
>
> **三个装饰器**各有独立类型。Chat 装饰器同时实现 `ChatCapable` + `ToolCallingCapable`，
> Embedding / Rerank 装饰器仅实现对应的能力接口。

```java
package com.smart.rag.infrastructure.llm.resilience;

/**
 * ResilientEmbeddingClient — Embedding 能力的弹性装饰器
 *
 * 策略矩阵：
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ embed        │ 指数退避重试   │ ✓            │ ✗            │
 * │ embedBatch   │ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 */
public class ResilientEmbeddingClient extends AbstractResilientClient<EmbeddingCapable> implements EmbeddingCapable {

    public ResilientEmbeddingClient(EmbeddingCapable delegate,
                                      CircuitBreaker circuitBreaker,
                                      RetryPolicy retryPolicy) {
        super(delegate, circuitBreaker, retryPolicy);
    }

    // ======== Embedding 操作（带弹性包装） ========

    @Override
    public float[] embed(String text, EmbeddingType type) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.embed(text, type)
            )
        );
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.embedBatch(texts, type)
            )
        );
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
```

```java
package com.smart.rag.infrastructure.llm.resilience;

/**
 * ResilientRerankClient — Rerank 能力的弹性装饰器
 *
 * 策略矩阵：
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ rerank       │ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 */
public class ResilientRerankClient extends AbstractResilientClient<RerankCapable> implements RerankCapable {

    public ResilientRerankClient(RerankCapable delegate,
                                   CircuitBreaker circuitBreaker,
                                   RetryPolicy retryPolicy) {
        super(delegate, circuitBreaker, retryPolicy);
    }

    // ======== Rerank 操作（带弹性包装） ========

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.rerank(request)
            )
        );
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.rerank(request, topN)
            )
        );
    }
}
```

### 7.8 Capability 策略（消除 switch 扩展瓶颈）

**问题**：`GenericOpenAiProvider.createClient()`、`LlmClientRegistry.wrapWithResilience()`、`LlmConfig.modelGroup()` 三处均使用 `switch(capability)` 硬编码能力分支。每新增一个能力（如 TTS、IMAGE）需同时改 3+ 处，违反 OCP。

**方案**：引入 `CapabilityStrategy` 接口，每种能力注册独立策略，新增能力只需添加一个策略实现。

```java
package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.config.ResilienceConfig;
import com.smart.rag.infrastructure.llm.resilience.*;
import org.springframework.lang.Nullable;

/**
 * 能力策略 — 封装特定能力的客户端创建与 Resilient 包装逻辑。
 * <p>
 * 每种 {@link LlmCapability} 注册一个实现，
 * 新增能力只需添加策略 Bean，无需修改 Provider 或 Registry 中的 switch。
 * <p>
 * 注册方式：{@code @Component} + Spring 自动收集，或手动注册到
 * {@link CapabilityStrategyRegistry}。
 */
public interface CapabilityStrategy {

    /** 此策略负责的能力类型 */
    LlmCapability capability();

    /**
     * 端点选择：从 ProviderConfig 中取出此能力对应的 endpoint。
     * <p>
     * 由 {@link LlmProvider#createClient(ModelCandidate)} 内部调用——
     * Provider 持有 {@code ProviderConfig}（包含 url + EndpointConfig），
     * 通过 Strategy 解析出具体能力的端点路径，再传回 {@code createClient()}。
     */
    String resolveEndpoint(ProviderConfig config);

    /**
     * 客户端创建：基于 Provider 解析后的连接参数 + candidate 构建原始 CapabilityClient。
     * <p>
     * 调用方是 {@link GenericOpenAiProvider#createClient(ModelCandidate)}：
     * Provider 从自身 {@code config()} 中解析 {@code baseUrl}、{@code apiKey}、{@code endpoint}，
     * 然后传递给此方法。Strategy 不感知 ProviderConfig，只消费已解析的参数——
     * 这保持了 {@link LlmProvider#createClient(ModelCandidate)} 签名的简洁性。
     */
    CapabilityClient createClient(String baseUrl, String endpoint,
                                  String apiKey, ModelCandidate candidate);

    /** Resilient 包装：将原始 Client 包装为带重试/熔断的装饰器 */
    CapabilityClient wrapWithResilience(CapabilityClient raw,
                                        CircuitBreaker circuitBreaker,
                                        RetryPolicy retryPolicy,
                                        @Nullable ProbeHandler probeHandler);
}
```

**三个内置实现**：

```java
package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.generic.*;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.resilience.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** CHAT 策略 — ChatCapable + ToolCallingCapable 自动检测 */
@Component
public class ChatCapabilityStrategy implements CapabilityStrategy {

    @Override public LlmCapability capability() { return LlmCapability.CHAT; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.endpoints().get(capability());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        return new GenericChatClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe) {
        // supportsToolCalling 由 ResilientChatClient 内部通过
        // delegate instanceof ToolCallingCapable 自动检测，无需外部传入
        return new ResilientChatClient((ChatCapable) raw, cb, retry, probe);
    }
}

/** RERANKING 策略 */
@Component
public class RerankCapabilityStrategy implements CapabilityStrategy {

    @Override public LlmCapability capability() { return LlmCapability.RERANKING; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.endpoints().get(capability());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        return new GenericRerankClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe) {
        return new ResilientRerankClient((RerankCapable) raw, cb, retry);
    }
}
```

**供应商差异化扩展点**（`ProviderClientFactory`）：

> 当供应商的某个能力使用原生 API 比 OpenAI 兼容 API 更优时，
> 实现此接口并注册为 `@Component`，CapabilityStrategy 自动发现并委托。
> 未注册工厂的能力 → Strategy 使用通用 Client（`GenericXxxClient`）。

```java
package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.LlmCapability;
/**
 * 供应商专用客户端工厂 — CapabilityStrategy 的扩展点
 * <p>
 * 用于供应商（如百炼）的特定能力需要使用原生 API 而非 OpenAI 兼容 API 的场景。
 * 通过 {@code @Component} 自注册，Strategy 在 {@code createClient()} 时自动发现。
 * <p>
 * <b>查找键</b>：{@code providerId() + ":" + capability()} 组合唯一确定一个工厂。
 * 同一供应商的不同能力可以注册不同的工厂（如百炼 Embedding 和 Reranking 各自独立）。
 */
public interface ProviderClientFactory {
    /** 此工厂负责的供应商 id（对应 YAML providers key） */
    String providerId();

    /** 此工厂负责的能力类型 */
    LlmCapability capability();

    /**
     * 创建专用客户端（使用原生 API）
     *
     * @param apiKey    供应商 API Key
     * @param candidate 模型候选声明
     * @return 专用能力客户端（已实现对应的 CapabilityClient 子接口）
     */
    CapabilityClient create(String apiKey, ModelCandidate candidate);
}
```

**内置策略的 createClient() 改造**（以 Embedding 为例）：

```java
package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.generic.GenericEmbeddingClient;
import com.smart.rag.infrastructure.llm.resilience.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EmbeddingCapabilityStrategy implements CapabilityStrategy {

    /** 供应商专用工厂索引（key = "providerId:capability"） */
    private final Map<String, ProviderClientFactory> providerFactories;

    public EmbeddingCapabilityStrategy(List<ProviderClientFactory> factories) {
        this.providerFactories = factories.stream()
            .filter(f -> f.capability() == LlmCapability.EMBEDDING)
            .collect(Collectors.toUnmodifiableMap(
                f -> f.providerId() + ":" + f.capability(),
                Function.identity()));
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        // 有专用工厂 → 委托（原生 API，保留 text_type / instruct 等特性）
        ProviderClientFactory factory = providerFactories.get(
            candidate.provider() + ":" + capability());
        if (factory != null) {
            return factory.create(apiKey, candidate);
        }
        // 通用路径 → OpenAI 兼容 Client
        return new GenericEmbeddingClient(baseUrl, endpoint, apiKey, candidate);
    }

    // ... 其他方法不变
}
```

**策略注册表**（替代 Registry 中的 switch）：

```java
/**
 * 能力策略注册表 — 自动收集所有 CapabilityStrategy Bean
 * <p>
 * 新增能力只需添加 {@code @Component} 策略实现，
 * 无需修改 Registry、Provider 或 Properties 中的任何 switch。
 */
@Component
public class CapabilityStrategyRegistry {

    private final Map<LlmCapability, CapabilityStrategy> strategies;

    public CapabilityStrategyRegistry(List<CapabilityStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toUnmodifiableMap(
                CapabilityStrategy::capability, Function.identity()));
    }

    public CapabilityStrategy get(LlmCapability cap) {
        CapabilityStrategy s = strategies.get(cap);
        if (s == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No CapabilityStrategy registered for " + cap);
        }
        return s;
    }

    public boolean hasStrategy(LlmCapability cap) {
        return strategies.containsKey(cap);
    }
}
```

> **扩展示例**：
> - **新增能力**：(1) 在 `LlmCapability` 添加枚举值，(2) 在 YAML `capabilities` 和 `endpoints` 中添加对应条目，(3) 实现 `CapabilityStrategy @Component`。系统自动注册——无需修改任何现有代码（详见 §7.1）。
> - **新增供应商差异化 Client**：(1) 实现 `ProviderClientFactory @Component`（声明 providerId + capability + create），(2) 实现专用 Client。Strategy 自动发现工厂并委托，无需修改 Strategy 或 Provider 代码（详见 §8.3）。

---

## 下一步

- [Phase 3: Provider + Registry + 配置](phase3-provider-registry.md) — GenericOpenAiProvider, LlmClientRegistry, LlmConfig, 配置体系
