# JDK 21 稳定 API 下的结构化并发工具设计

> 目标：不启用 JDK 预览特性，不修改当前 JVM 启动参数，在 JDK 21 稳定 API 上实现一个适配本项目的结构化并发风格工具。
>
> 该工具不是直接复刻 JDK 预览 API，而是抽取结构化并发对本项目有价值的语义：父子任务生命周期、统一等待、失败传播、自动取消、上下文传递和可观测性。

## 1. 背景

JDK 21 中虚拟线程已经稳定，可以通过 `Executors.newVirtualThreadPerTaskExecutor()` 使用；但结构化并发仍是预览特性，直接使用需要启用 preview 编译和运行参数。当前项目不能修改 JVM 运行方式，因此不应直接依赖预览 API。

后续 JEP 505 / 525 / 533 的演进虽然仍处于预览阶段，但已经沉淀出几个稳定的设计方向，本文档将其作为设计约束而不是 API 依赖：

- 结构化作用域必须有 owner 线程，`fork` / `join` / `close` 只能由 owner 在线性代码块内调用。
- 任务结果必须在统一等待之后读取，`Subtask.result()` 不应伪装成阻塞式 `Future.get()`。
- 完成策略不只是“是否取消”，还必须定义失败、超时和结果收集语义。
- “等待全部但不检查失败”是危险 API，需要显式失败处理或至少有关闭时安全网。
- 作用域关闭时不仅取消未完成任务，还必须等待任务终止，否则无法保证没有线程泄漏。
- 可观测性应包含作用域级汇总，而不只是单个子任务日志。

项目内已经存在多处并发编排：

| 位置 | 当前模式 | 主要问题 |
|------|----------|----------|
| `HybridSearchService` | `CompletableFuture.supplyAsync()` + `thenCombine()` 并行向量/BM25 检索 | 部分降级、整体超时、上下文传播分散 |
| `ModelRegistryRefresher` | `CompletableFuture` + 手写 MDC 恢复 | MDC 传递重复实现 |
| `SandboxService` | `ExecutorService` + 手写 MDC 恢复 | 作用域生命周期不统一 |
| `StandardStrategy` / `FastTrackStrategy` | 多阶段 `CompletableFuture.allOf()` | 异常聚合和取消语义不直观 |
| `DatasetGenerator` | 自建线程池 + `CompletableFuture` | 并发上限、关闭、异常传播需要统一 |

这些场景并不都应该立即迁移，但它们说明项目需要一个统一的“请求内并发编排”抽象，减少重复代码和语义漂移。

## 2. 设计目标

### 2.1 目标

- 提供类似结构化并发的作用域 API：所有子任务必须挂在父作用域下。
- 父作用域退出时，自动取消未完成任务。
- 支持等待全部、超时等待、失败即取消、全部收集等 Phase 1 策略，并为成功即取消等高级策略保留扩展点。
- Phase 1 支持 MDC 显式传递；请求上下文、安全上下文等 ThreadLocal 类上下文在 Phase 3 按真实使用方扩展。
- 支持任务命名、耗时记录、失败聚合和可观测性。
- 只使用 JDK 21 稳定 API 和项目已有 Spring / SLF4J 能力。
- 不新增第三方依赖。

### 2.2 非目标

- 不承诺 100% 兼容 JDK 预览 `StructuredTaskScope` API。
- 不通过反射读取或修改 JVM 内部 `ThreadLocalMap`。
- 不强制杀死不响应中断的任务。
- 不替代所有 `CompletableFuture`。长期后台任务、事件驱动链路、缓存预热、无父请求生命周期的异步任务仍可继续使用现有模型。
- 不在 Phase 1 接管 Reactor `Flux` 的完整流式生命周期。

## 3. 设计原则

### 3.1 结构化生命周期

并发任务不能脱离父调用栈独立存在。业务代码通过 `try-with-resources` 创建作用域，作用域内 `fork` 的任务由作用域统一管理：

```java
try (TaskScope scope = scopedTasks.open("hybrid-search")) {
    Subtask<List<ScoredDocument>> vector = scope.fork("vector-search", () -> vectorSearch(query));
    Subtask<List<ScoredDocument>> bm25 = scope.fork("bm25-search", () -> bm25Search(query));

    scope.joinUntil(Duration.ofSeconds(3));
    scope.throwIfFailed();

    return merge(vector.result(), bm25.result());
}
```

调用方看到的是同步风格代码，实际并发由 scope 内部处理。这样比直接暴露多个 `CompletableFuture` 更容易保证：

- 子任务不会忘记等待。
- 失败不会被隐藏在异步链尾部。
- 请求结束时不会残留任务。
- 上下文传播和清理在一个地方完成。

### 3.2 Owner 线程约束

本工具强制 owner 线程约束：创建 `TaskScope` 的线程就是 owner，只有 owner 能调用 `fork()`、`join()`、`joinUntil()`、`throwIfFailed()` 和 `close()`。

```java
try (TaskScope scope = scopedTasks.open("parent")) {
    executor.submit(() -> scope.fork("illegal", this::work)); // 禁止
}
```

这种限制是结构化并发的核心。否则 scope 可以被传递给其它线程继续 fork，任务树就不再由当前代码块决定，`try-with-resources` 也无法可靠表达父子生命周期。

实现上建议：

```java
final class DefaultTaskScope implements TaskScope {

    private final Thread ownerThread = Thread.currentThread();

    private void ensureOwner(String operation) {
        if (Thread.currentThread() != ownerThread) {
            throw new ScopeViolationException(
                operation + " must be called from the scope owner thread");
        }
    }
}
```

`ScopeViolationException` 属于编程错误，表示调用方破坏了结构化约束。它不应被业务层转换成可恢复错误。

### 3.3 显式上下文传播

参考 `TransmittableThreadLocal` 的核心思想：不要要求业务任务关心上下文传递，而是在任务提交前捕获父线程上下文，在子线程执行时恢复，执行结束后还原。

本设计不尝试“自动发现所有 ThreadLocal”，而是采用白名单式 `ContextCarrier`。原因是 JDK 没有稳定公开 API 可以安全枚举所有 ThreadLocal；反射读取内部结构会引入 JVM 兼容风险，也违背本设计“不修改 JVM、不依赖非稳定能力”的目标。

## 4. Phase 迭代路线

本文档描述完整方向，但实现必须分 Phase 推进。Phase 1 只落地最小可用闭环，后续能力在真实使用场景出现后再扩展。

| Phase | 目标 | 状态 | 退出条件 |
|-------|------|------|----------|
| Phase 1 | 建立最小结构化并发作用域 | 必做 | 小型测试服务通过 owner、fork、join、timeout、cancel、MDC、异常聚合测试 |
| Phase 2 | 迁移第一个真实业务场景 | 已完成 | `HybridSearchService` 在保持 partial-success 行为的前提下完成迁移 |
| Phase 3 | 扩展上下文、executor 和观测能力 | 已启动 | `ModelRegistryRefresher` 迁移为第二个真实使用方；继续按重复需求增量扩展 |
| Phase 4 | 补充高级策略和流式边界能力 | 延后 | 有竞速成功、复杂 partial success 或 Reactor 深度整合需求 |
| Phase 5 | 对齐未来稳定 JDK API | 延后 | JDK 结构化并发转正且项目 JVM 可升级 |

### 4.1 Phase 1：最小可用闭环

Phase 1 目标是证明这个工具能在本项目稳定运行，而不是一次性实现所有策略。

必须实现：

- `ScopedTasks.open(String name)`、`ScopedTasks.open(String name, ScopePolicy policy)` 和 `ScopedTasks.open(String name, ScopeOptions options)`。
- `TaskScope.fork(String, Callable<T>)` 和 `fork(String, Runnable)`。
- owner 线程约束。
- join 后禁止继续 fork。
- `join()` / `joinUntil(Duration)`。
- `ScopeOptions.builder(String)`、`defaultTimeout`、`closeTimeout`。
- 默认 `VIRTUAL_THREAD_PER_TASK` executor；`PLATFORM_THREAD_POOL` / `SHARED_EXECUTOR` 只保留设计位，不在 Phase 1 暴露生产配置。
- `SHUTDOWN_ON_FAILURE`。
- `COLLECT_ALL`。
- `try-with-resources` 关闭时取消未完成任务。
- MDC 上下文传递。
- `ScopeExecutionException`、`ScopeTimeoutException`、`ScopeClosedException`、`ScopeViolationException`。
- `SubtaskException` 及其三个子类。
- completion signal + owner 线程驱动策略的 join 实现。

明确不实现：

- `SHUTDOWN_ON_SUCCESS`。
- `PARTIAL_SUCCESS_OR_THROW`。
- `ScopeJoiner<R>`。
- Spring Security / RequestContext carrier。
- Micrometer 指标。
- Reactor / `Flux` 生命周期整合。
- 嵌套 scope 的专门优化。Phase 1 只要求不吞中断，并在测试中覆盖基本取消传播。
- 真实业务迁移。Phase 1 先用小型测试服务验证语义。

### 4.2 Phase 2：首个业务迁移

Phase 2 才迁移真实业务。首选候选是 `HybridSearchService`，但不能直接使用 `SHUTDOWN_ON_FAILURE`，因为当前行为是部分降级：vector 或 BM25 单分支失败时继续融合，两者都失败才抛业务异常。

Phase 2 必须先写行为表：

| 场景 | 当前行为 | 迁移后要求 |
|------|----------|------------|
| vector 成功，BM25 成功 | 融合两边结果 | 保持 |
| vector 失败，BM25 成功 | vector 降级为空，继续 BM25 | 保持 |
| vector 成功，BM25 失败 | BM25 降级为空，继续 vector | 保持 |
| vector 失败，BM25 失败 | 抛 `BusinessException` | 保持 |
| 整体超时 | 抛超时相关异常 | 行为需显式确认后锁测试 |

实现方式：

- 优先使用 `COLLECT_ALL` + 调用方显式判断成功/失败。
- 如果相同 partial-success 模式出现第二处，再新增 `PARTIAL_SUCCESS_OR_THROW`。
- 迁移前必须先补回归测试，迁移后再替换实现。

Phase 2 落地结果：

- `HybridSearchService.hybridSearch()` 已从 `CompletableFuture.supplyAsync()` / `thenCombine()` 迁移到 `TaskScope`。
- 策略使用 `COLLECT_ALL`，等待后读取每个 `Subtask.exception()` 判断分支是否失败。
- vector 或 BM25 单分支失败时降级为空列表继续融合；两者都失败时抛 `BusinessException("向量检索和 BM25 检索均不可用")`。
- 回归测试覆盖 vector 成功 + BM25 成功、vector 失败 + BM25 成功、vector 成功 + BM25 失败、两者都失败。
- 暂不新增 `PARTIAL_SUCCESS_OR_THROW`，直到第二个真实业务场景出现相同模式。

### 4.3 Phase 3：上下文、executor 和观测扩展

Phase 3 处理“重复使用后自然出现”的基础设施需求：

- Spring Security context carrier。
- RequestContext carrier。
- 共享 executor 模式。
- 平台线程池模式。
- 配置属性类，例如 `ScopedTaskProperties`。
- scope 级结构化日志增强。
- Micrometer 指标。
- 跨请求 bulkhead / 限流设计。

Phase 3 的准入条件：至少两个业务场景已经使用 Phase 1/2 能力，且出现重复配置或重复观测需求。

Phase 3 当前落地结果：

- `ModelRegistryRefresher.refresh()` 已从 `CompletableFuture.supplyAsync()` + 静态虚拟线程 executor + 手写 MDC 恢复，迁移到 `ScopedTasks`。
- 策略使用 `COLLECT_ALL`，每个 Provider 拉取失败时转成 `ProviderResult`，继续保留“单个 Provider 失败不影响其它 Provider”的容错语义。
- MDC 继承、虚拟线程 executor 生命周期、任务命名和 scope 汇总日志统一由 `TaskScope` 承担。
- 回归测试覆盖成功 Provider 注册、失败 Provider 隔离、全部 Provider 失败不替换已有 registry、MDC 继承和调用方线程 MDC 不被污染。
- 暂不新增 `ScopedTaskProperties`、共享 executor、平台线程池、SecurityContext / RequestContext carrier 或 Micrometer 指标，直到第三个使用方或明确配置/观测重复需求出现。

### 4.4 Phase 4：高级策略和流式边界

Phase 4 处理高级并发模式：

- `SHUTDOWN_ON_SUCCESS`。
- `PARTIAL_SUCCESS_OR_THROW`。
- `QuorumSuccessPolicy`。
- `ScopeJoiner<R>` 或等价的强类型结果收集抽象。
- Reactor `Flux` / SSE 与 scope 生命周期绑定。
- 更完整的嵌套 scope 结构违规检测。

这些能力 Phase 1 不做。只有当业务明确需要竞速成功、强类型聚合结果或流式深度整合时再实现。

### 4.5 Phase 5：JDK 稳定 API 适配

当 JDK 结构化并发转正且项目 JVM 可升级时，再评估：

- 是否用 JDK API 替换内部实现。
- 是否保留本项目 `TaskScope` 门面作为兼容层。
- 是否迁移 `ScopePolicyHandler` 到 JDK Joiner 风格。

在此之前，本项目工具只吸收语义，不依赖 preview API。

## 5. 核心抽象

### 5.1 ScopedTasks

`ScopedTasks` 是业务入口门面，负责创建 `TaskScope`。

```java
public interface ScopedTasks {

    TaskScope open(String name);

    TaskScope open(String name, ScopePolicy policy);

    TaskScope open(String name, ScopeOptions options);
}
```

`open(String name, ScopePolicy policy)` 是 Phase 1 的轻量便利入口，等价于使用默认 `ScopeOptions` 并覆盖策略。它只能接受 Phase 1 已实现的策略；高级策略必须等对应 Phase 落地后再进入生产枚举。

推荐实现类：

```text
com.smart.rag.common.concurrent.DefaultScopedTasks
```

### 5.2 TaskScope

`TaskScope` 是结构化并发作用域，生命周期由 `try-with-resources` 管理。

```java
public interface TaskScope extends AutoCloseable {

    <T> Subtask<T> fork(String name, Callable<T> task);

    default Subtask<Void> fork(String name, Runnable task) {
        return fork(name, () -> {
            task.run();
            return null;
        });
    }

    void join();

    void joinUntil(Duration timeout);

    void throwIfFailed();

    List<Subtask<?>> subtasks();

    @Override
    void close();
}
```

契约：

- `fork()`、`join()`、`joinUntil()`、`throwIfFailed()`、`close()` 只能由 owner 线程调用。
- `fork()` 只能在 scope 未关闭时调用。
- `join()` 按 `ScopeOptions.defaultTimeout` 决定是无限等待还是限时等待。
- `joinUntil()` 使用调用方显式传入的超时，优先级高于 `ScopeOptions.defaultTimeout`。
- `throwIfFailed()` 将已记录失败聚合后抛出。
- `close()` 必须取消所有未完成任务、等待任务终止并释放 scope 拥有的资源。

### 5.3 Subtask

`Subtask` 是业务可见的子任务句柄，封装底层 `Future`。

```java
public interface Subtask<T> {

    String name();

    TaskState state();

    T result();

    Throwable exception();

    boolean cancel();
}
```

`TaskState`：

```java
public enum TaskState {
    NEW,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
```

业务层不直接持有 `Future`，避免绕开 scope 的生命周期管理。

`result()` 是非阻塞方法，必须在 `join()` 或 `joinUntil(Duration)` 返回之后调用。它不等价于 `Future.get()`，不会隐式等待任务完成。

```java
/**
 * 获取子任务的执行结果。
 *
 * <p>此方法为非阻塞操作，必须在 TaskScope.join() 或
 * TaskScope.joinUntil(Duration) 之后调用。
 *
 * @throws SubtaskNotCompletedException 如果任务尚未完成
 * @throws SubtaskFailedException 如果任务执行失败
 * @throws SubtaskCancelledException 如果任务被取消
 */
T result();
```

`exception()` 只返回任务自身失败原因。任务未完成、成功或取消时返回 `null`。

### 5.4 ScopeOptions

```java
public record ScopeOptions(
    String name,
    ScopePolicy policy,
    ExecutorMode executorMode,
    int maxConcurrency,
    Duration defaultTimeout,
    Duration closeTimeout,
    boolean executorOwnedByScope,
    boolean inheritMdc,
    boolean inheritSecurityContext,
    boolean inheritRequestContext
) {
    public static ScopeOptions shutdownOnFailure(String name) {
        return new ScopeOptions(
            name,
            ScopePolicy.SHUTDOWN_ON_FAILURE,
            ExecutorMode.VIRTUAL_THREAD_PER_TASK,
            0,
            Duration.ZERO,
            Duration.ofSeconds(5),
            true,
            true,
            false,
            false
        );
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private ScopePolicy policy = ScopePolicy.SHUTDOWN_ON_FAILURE;
        private ExecutorMode executorMode = ExecutorMode.VIRTUAL_THREAD_PER_TASK;
        private int maxConcurrency;
        private Duration defaultTimeout = Duration.ZERO;
        private Duration closeTimeout = Duration.ofSeconds(5);
        private boolean executorOwnedByScope = true;
        private boolean inheritMdc = true;
        private boolean inheritSecurityContext;
        private boolean inheritRequestContext;

        private Builder(String name) {
            this.name = name;
        }

        public Builder policy(ScopePolicy policy) { this.policy = policy; return this; }
        public Builder executorMode(ExecutorMode executorMode) { this.executorMode = executorMode; return this; }
        public Builder maxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; return this; }
        public Builder defaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; return this; }
        public Builder closeTimeout(Duration closeTimeout) { this.closeTimeout = closeTimeout; return this; }
        public Builder executorOwnedByScope(boolean value) { this.executorOwnedByScope = value; return this; }
        public Builder inheritMdc(boolean value) { this.inheritMdc = value; return this; }
        public Builder inheritSecurityContext(boolean value) { this.inheritSecurityContext = value; return this; }
        public Builder inheritRequestContext(boolean value) { this.inheritRequestContext = value; return this; }

        public ScopeOptions build() {
            return new ScopeOptions(name, policy, executorMode, maxConcurrency, defaultTimeout,
                closeTimeout, executorOwnedByScope, inheritMdc, inheritSecurityContext,
                inheritRequestContext);
        }
    }
}
```

`ScopeOptions` 保持 record 作为不可变值对象，但调用方必须通过 Builder 渐进配置，避免 10 个构造参数在调用点产生顺序错误。

Phase 1 只启用 `inheritMdc`。`inheritSecurityContext` 和 `inheritRequestContext` 是 Phase 3 预留开关，默认必须为 `false`；在对应 carrier 未注册前，调用方不应启用它们，避免文档承诺超出实现范围。

`maxConcurrency = 0` 表示不在 scope 层限流，由调用方或底层资源控制。对外部模型调用、RAG 检索、工具调用等场景，可配置正数并发上限。

`defaultTimeout` 的优先级：

1. 调用 `joinUntil(Duration)` 时，以该显式超时为准。
2. 调用 `join()` 且 `defaultTimeout > 0` 时，按 `defaultTimeout` 限时等待。
3. 调用 `join()` 且 `defaultTimeout == Duration.ZERO` 时，无限等待直到所有任务完成或策略提前终止。

`closeTimeout` 是 `close()` 等待子任务终止的硬上限。Phase 1 必须提供默认值，不能留给实现阶段临时决定。建议默认 5 秒；如果调用方配置了 `defaultTimeout` 且需要更严格的关闭等待，可以显式设置 `closeTimeout`。

`executorOwnedByScope` 区分 executor 生命周期：

- `true`：executor 由本 scope 创建，`close()` 时可以关闭。
- `false`：executor 是 Spring 或外部共享资源，`close()` 只能取消本 scope 的任务，不能关闭 executor。

## 6. 策略语义

### 6.1 ScopePolicy

```java
public enum ScopePolicy {
    SHUTDOWN_ON_FAILURE,
    COLLECT_ALL
}
```

| 策略 | 语义 | 适用场景 |
|------|------|----------|
| `SHUTDOWN_ON_FAILURE` | 任一任务失败后取消其它未完成任务 | 多个必要子任务并行，任一失败都无法继续 |
| `COLLECT_ALL` | 等待全部结束，不自动 fail-fast | 批量评估、ETL 多候选处理、允许部分降级的检索 |

Phase 1 建议 `open(String name)` 默认使用 `SHUTDOWN_ON_FAILURE`，但迁移现有代码时必须按当前业务语义选择策略。允许部分成功继续的链路不能直接套用 fail-fast。

`COLLECT_ALL` 不是“忽略失败”。它只是不在第一个失败时取消其它任务。调用方必须显式检查每个 `Subtask`，或调用 `throwIfFailed()` 统一抛出失败。为了避免失败静默，`DefaultTaskScope.close()` 必须在 `COLLECT_ALL` 模式下检查未处理失败；如果发现调用方既没有调用 `throwIfFailed()`，也没有读取失败子任务的 `exception()`，应记录 `WARN`：

```java
log.warn("TaskScope '{}' closed with {} unhandled failure(s). "
        + "Call throwIfFailed() or inspect subtask.exception() explicitly.",
    name, unhandledFailureCount);
```

`SHUTDOWN_ON_SUCCESS` 属于 Phase 4 高级策略，Phase 1 不进入 `ScopePolicy` 生产枚举。未来引入时必须满足以下完整语义，不能只实现“任一成功即取消”的 happy path：

| 条件 | `throwIfFailed()` | 成功结果读取 |
|------|-------------------|--------------|
| 任一任务成功 | 不抛异常，其它未完成任务被取消 | 读取成功的 `Subtask.result()` |
| 全部任务失败 | 抛出 `ScopeExecutionException`，包含全部失败 | 所有失败任务 `result()` 抛 `SubtaskFailedException` |
| 超时且已有成功 | 不抛异常，取消未完成任务 | 读取已成功任务的结果 |
| 超时且无成功 | 抛出 `ScopeTimeoutException`，包含已知失败 | 未完成任务 `result()` 抛 `SubtaskCancelledException` 或 `SubtaskNotCompletedException` |

### 6.2 策略模式

`TaskScope` 不直接硬编码所有完成策略，而是委托给策略处理器：

```java
interface ScopePolicyHandler {

    void onSuccess(SubtaskInternal<?> task, ScopeState state);

    void onFailure(SubtaskInternal<?> task, Throwable error, ScopeState state);

    void onTimeout(ScopeState state);

    boolean shouldStop(ScopeState state);
}
```

实现类：

```text
ShutdownOnFailurePolicy
CollectAllPolicy
```

Phase 1 只实现 `ShutdownOnFailurePolicy` 和 `CollectAllPolicy`。后续如果出现“任一成功即可返回”或“至少 N 个任务成功即可返回”的需求，可以新增 `ShutdownOnSuccessPolicy`、`QuorumSuccessPolicy`，而不修改 `DefaultTaskScope` 主流程。

`onTimeout()` 是策略的一部分，而不是 `joinUntil()` 的硬编码分支。不同策略的超时含义不同：

| 策略 | 超时语义 |
|------|----------|
| `SHUTDOWN_ON_FAILURE` | 超时视为 scope 失败，取消未完成任务并抛出 `ScopeTimeoutException` |
| `COLLECT_ALL` | 取消未完成任务，保留已完成结果和已知失败，调用方继续按收集结果处理 |

Phase 4 引入 `SHUTDOWN_ON_SUCCESS` 时，其超时语义必须是：如果已有成功结果，则取消未完成任务并允许读取成功结果；否则抛出 `ScopeTimeoutException`。

如果未来需要让策略直接返回强类型结果，可以在当前 `ScopePolicyHandler` 之外新增 `ScopeJoiner<R>` 抽象，借鉴 JEP Joiner 的“策略与结果收集一体化”设计。但 Phase 1 不引入该复杂度。

### 6.3 部分成功策略扩展点

当前项目已经存在“部分成功即可继续”的业务语义，例如混合检索中 vector 和 BM25 任一分支失败时降级为空列表，两者都失败时才抛业务异常。该语义不能用 `SHUTDOWN_ON_FAILURE` 表达。

后续可新增：

```java
public enum ScopePolicy {
    SHUTDOWN_ON_FAILURE,
    SHUTDOWN_ON_SUCCESS,
    COLLECT_ALL,
    PARTIAL_SUCCESS_OR_THROW
}
```

`PARTIAL_SUCCESS_OR_THROW` 语义：

| 条件 | 行为 |
|------|------|
| 至少一个任务成功 | 保留成功结果，失败任务由调用方按默认值或降级逻辑处理 |
| 全部任务失败 | 抛出 `ScopeExecutionException`，包含全部失败 |
| 超时且已有成功 | 取消未完成任务，允许基于部分成功结果继续 |
| 超时且无成功 | 抛出 `ScopeTimeoutException` |

Phase 2 可先用 `COLLECT_ALL` + 调用方显式判断实现该语义，等出现第二个相同模式后再抽成独立策略，避免过早扩展。

当前落地约束：`HybridSearchService` 已采用该过渡方案。后续维护时不要在该链路改用 `SHUTDOWN_ON_FAILURE`，否则任一检索分支失败都会取消另一分支，破坏 partial-success 降级语义。

## 7. 上下文传递设计

### 7.1 ContextCarrier

```java
public interface ContextCarrier<S> {

    S capture();

    S restore(S snapshot);

    void clear(S previous);
}
```

语义：

- `capture()`：在父线程提交任务前捕获上下文快照。
- `restore()`：在子线程执行任务前恢复快照，并返回子线程原上下文。
- `clear()`：任务结束后恢复子线程原上下文，防止线程复用导致污染。

### 7.2 MDC 示例

```java
public final class MdcContextCarrier implements ContextCarrier<Map<String, String>> {

    @Override
    public Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public Map<String, String> restore(Map<String, String> snapshot) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
        return previous;
    }

    @Override
    public void clear(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
```

### 7.3 ContextSnapshot

```java
public final class ContextSnapshot {

    private final List<CapturedContext<?>> contexts;

    public static ContextSnapshot capture(List<ContextCarrier<?>> carriers) {
        // 逐个 carrier 捕获父线程上下文。
    }

    public ContextRestorer restore() {
        // 逐个 carrier 恢复子线程上下文，返回 closeable restorer。
    }
}
```

`ContextRestorer` 使用 `AutoCloseable`：

```java
try (ContextRestorer ignored = snapshot.restore()) {
    return delegate.call();
}
```

### 7.4 装饰器模式

上下文传递通过装饰原始任务完成：

```java
final class ContextAwareCallable<T> implements Callable<T> {

    private final Callable<T> delegate;
    private final ContextSnapshot snapshot;

    @Override
    public T call() throws Exception {
        try (ContextRestorer ignored = snapshot.restore()) {
            return delegate.call();
        }
    }
}
```

任务提交链路：

```text
业务 Callable
    -> ContextAwareCallable
    -> ObservedCallable
    -> FutureTask / ExecutorService
```

这与 `TransmittableThreadLocal` 的设计思想一致：不改变业务任务本身，而是在任务和线程池之间增加一层包装，让原始 `ThreadLocal` 能跨线程传递。

差异是：本项目工具采用显式 carrier 白名单，而不是扩展 ThreadLocal 类型本身。这样更符合项目约束，也更容易做安全审计。

## 8. Executor 设计

### 8.1 ExecutorMode

```java
public enum ExecutorMode {
    VIRTUAL_THREAD_PER_TASK,
    PLATFORM_THREAD_POOL,
    SHARED_EXECUTOR
}
```

推荐默认：

- IO 密集型请求内任务：`VIRTUAL_THREAD_PER_TASK`
- CPU 密集型任务：`PLATFORM_THREAD_POOL`
- 已由 Spring 管理的专用线程池：`SHARED_EXECUTOR`

Phase 1 只交付 `VIRTUAL_THREAD_PER_TASK` 默认路径。`PLATFORM_THREAD_POOL` 和 `SHARED_EXECUTOR` 的代码分支可以作为受测内部扩展点保留，但不能在配置层对业务开放；等 Phase 3 出现共享 executor / 平台线程池的真实复用需求后，再补配置属性、生命周期测试和自动配置。

### 8.2 工厂模式

```java
interface ScopeExecutorFactory {

    ExecutorService create(ScopeOptions options);
}
```

默认实现：

```java
public final class DefaultScopeExecutorFactory implements ScopeExecutorFactory {

    @Override
    public ExecutorService create(ScopeOptions options) {
        return switch (options.executorMode()) {
            case VIRTUAL_THREAD_PER_TASK -> Executors.newVirtualThreadPerTaskExecutor();
            case PLATFORM_THREAD_POOL -> createBoundedPlatformPool(options);
            case SHARED_EXECUTOR -> sharedExecutor(options);
        };
    }
}
```

如果使用 `newVirtualThreadPerTaskExecutor()`，scope 关闭时应关闭该 executor。若使用 Spring 注入的共享 executor，scope 只能取消任务，不能关闭共享 executor。

`DefaultTaskScope` 不应根据 enum 猜测是否关闭 executor，而应依赖 `ScopeOptions.executorOwnedByScope()`：

```java
if (options.executorOwnedByScope()) {
    executor.shutdown();
}
```

默认规则：

| 模式 | 默认 owner | `close()` 行为 |
|------|------------|----------------|
| `VIRTUAL_THREAD_PER_TASK` | scope | 取消未完成任务，等待终止，关闭 executor |
| `PLATFORM_THREAD_POOL` | scope | 取消未完成任务，等待终止，关闭 executor |
| `SHARED_EXECUTOR` | 外部 | 只取消本 scope 任务，不关闭 executor |

## 9. TaskScope 执行流程

### 9.1 fork

```text
1. 校验当前线程是 owner。
2. 校验 scope 未关闭。
3. 捕获父线程上下文 ContextSnapshot。
4. 用 ContextAwareCallable 装饰业务 Callable。
5. 用 ObservedCallable 装饰任务，记录任务名、状态、耗时和异常。
6. 如配置 maxConcurrency，则用 Semaphore 控制并发进入。
7. 提交到 ExecutorService。
8. 创建 DefaultSubtask 并注册到 ScopeState。
```

### 9.2 join

```text
1. 校验当前线程是 owner。
2. 校验 scope 尚未 joined。
3. 读取当前 scope 已注册任务快照。
4. 如果 defaultTimeout > 0，则转换为限时等待；否则无限等待。
5. 等待任一子任务完成信号。
6. 在 owner 线程中读取已完成任务状态，并调用 ScopePolicyHandler。
7. 如果策略要求提前停止，则 cancel 未完成任务。
8. 所有任务终止、策略提前停止或超时后，标记 scope 已 joined。
```

本工具与 JDK 结构化并发保持一致：`join()` / `joinUntil()` 只能成功调用一次。调用 `join` 后禁止继续 `fork` 新任务。原因是结构化作用域应该只有一个清晰的 forking phase 和一个 joining phase；允许 join 后再次 fork 会迫使实现区分“旧任务”和“新任务”，破坏生命周期清晰度。

### 9.3 joinUntil

```text
1. 校验当前线程是 owner。
2. 校验 scope 尚未 joined。
3. 计算 deadline。
4. 等待任一子任务完成信号。
5. 任务完成时仍按 ScopePolicyHandler 处理 success/failure。
6. 超时后取消未完成任务。
7. 调用 ScopePolicyHandler.onTimeout(state) 决定抛异常还是保留部分结果。
8. 保留已失败任务作为 suppressed，便于排查。
```

### 9.4 close

```text
1. 校验当前线程是 owner。
2. 标记 scope closing。
3. 取消所有 NEW/RUNNING 状态任务。
4. 在 `ScopeOptions.closeTimeout` 内等待已取消任务结束，确保 close 不无限阻塞。
5. 如果 policy == COLLECT_ALL 且存在未处理失败，记录 WARN。
6. 记录 scope 级汇总日志。
7. 如果 executor 由 scope 创建，则 shutdown。
8. 清理内部任务列表和上下文引用。
9. 多次 close 必须幂等。
```

`close()` 必须尽力等待任务终止，而不是只调用 `cancel(true)` 后立即返回。否则父作用域已经退出，子任务仍可能继续运行，结构化生命周期保证会失效。但等待必须有硬上限：如果子任务吞掉中断或卡在不可中断 IO 中，超过 `closeTimeout` 后记录 `WARN` 并继续关闭 scope 拥有的资源。

## 10. 实现约束

### 10.1 join 等待机制

Phase 1 实现必须指定一个确定的等待机制，避免实现者在 `Future.get()`、`CompletableFuture.allOf()`、`CountDownLatch`、`Phaser` 之间自行选择导致语义偏差。

推荐机制：

- 每个 `DefaultSubtask` 内部持有一个 `CompletableFuture<SubtaskInternal<?>> completionSignal`。
- `ObservedCallable` 在任务成功、失败或取消时完成该 signal。
- owner 线程的 `join()` / `joinUntil()` 循环使用 `CompletableFuture.anyOf(...)` 等待任一任务完成。
- 每次任一任务完成后，owner 线程统一读取 `ScopeState` 并调用 `ScopePolicyHandler`。

伪代码：

```java
while (!state.allTerminal()) {
    if (policyHandler.shouldStop(state)) {
        cancelUnfinished();
        break;
    }

    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
        cancelUnfinished();
        policyHandler.onTimeout(state);
        break;
    }

    CompletableFuture.anyOf(activeCompletionSignals())
        .get(remaining, TimeUnit.NANOSECONDS);

    drainCompletedSignalsOnOwnerThread();
}
```

这里使用 `CompletableFuture` 只是内部完成信号，不把 `CompletableFuture` 暴露给业务层，也不回到业务代码直接编排异步链的模式。

禁止使用的实现方式：

- 逐个 `Future.get(timeout)`：会把并发等待退化为按任务顺序等待。
- 只用 `CompletableFuture.allOf()`：无法在第一个失败时及时执行 `SHUTDOWN_ON_FAILURE`。
- 使用轮询：引入延迟和无谓 CPU 消耗。

### 10.2 线程模型

| 操作 | 执行线程 | 同步要求 |
|------|----------|----------|
| `fork` / `join` / `joinUntil` / `throwIfFailed` / `close` | owner 线程 | 通过 owner 约束保证单线程生命周期控制 |
| `Callable.call()` | 任务线程 | 不访问 scope 生命周期方法 |
| `SubtaskInternal.markRunning/markSuccess/markFailed/markCancelled` | 任务线程 | 必须线程安全 |
| `ScopePolicyHandler.onSuccess/onFailure/onTimeout/shouldStop` | owner 线程 | 策略实现不要求线程安全 |
| `Subtask.state()` / `Subtask.exception()` | 任意线程读取 | 字段必须具备可见性保证 |

`ScopePolicyHandler` 回调只能在 owner 线程中执行。任务线程只负责写入子任务终态并完成 signal，不直接调用策略。这样可以把策略实现保持为普通对象，避免把业务策略和并发控制纠缠在一起。

### 10.3 同步原语

本项目运行在 JDK 21。JDK 21 虚拟线程在 `synchronized` 块或方法内执行长时间阻塞操作会 pin 住 carrier 线程。JDK 24 的 JEP 491 改善了该问题，但本项目不能假设运行在 JDK 24+。

实现要求：

- `ScopeState`、`DefaultSubtask` 等并发状态优先使用 `AtomicReference`、`AtomicBoolean`、`ConcurrentLinkedQueue`、`CopyOnWriteArrayList` 或 `ReentrantLock`。
- 不在 `synchronized` 块或 `synchronized` 方法内执行 `Future.get()`、`CompletableFuture.get()`、IO、sleep、join、等待 signal 等阻塞操作。
- 如需互斥并且可能包裹阻塞等待，使用 `ReentrantLock` 并在 `finally` 中释放。
- 短小纯内存临界区可以使用 `synchronized`，但 Phase 1 建议统一避免，以减少误用。

### 10.4 ContextCarrier 泛型擦除

`ContextCarrier<S>` 的 `S` 类型在运行期会被擦除。`ContextSnapshot` 内部不可避免要保存异构 carrier 和 snapshot：

```java
record CapturedContext<S>(ContextCarrier<S> carrier, S snapshot) {}
```

实现上可以在 `ContextSnapshot.capture(List<ContextCarrier<?>>)` 内集中处理 unchecked cast，并把警告限制在这一处。调用方不应直接操作 `CapturedContext<?>`。

### 10.5 嵌套 scope 与中断传播

嵌套 scope 是允许的，但必须遵守 owner 线程和关闭顺序：

```java
try (TaskScope outer = scopedTasks.open("outer")) {
    outer.fork("child", () -> {
        try (TaskScope inner = scopedTasks.open("inner")) {
            inner.fork("work", this::slowWork);
            inner.join();
        }
        return null;
    });
    outer.joinUntil(Duration.ofSeconds(1));
}
```

当外层 scope 超时取消 `child` 时，`child` 任务线程会收到中断。如果它正阻塞在 `inner.join()`，`inner.join()` 必须响应 `InterruptedException`：取消 inner scope 未完成任务，恢复当前线程中断状态，并把中断传播为 scope 失败或取消信号。

实现要求：

- `join()` / `joinUntil()` 不能吞掉 `InterruptedException`。
- 捕获 `InterruptedException` 后必须调用 `Thread.currentThread().interrupt()` 恢复中断状态。
- 被中断的 scope 必须取消自身未完成任务，防止嵌套任务泄漏。
- 测试必须覆盖 parent timeout -> child interrupted -> inner scope cancelled 的传播链。

### 10.6 Subtask 异常层级

为降低调用方 catch 成本，所有 `Subtask.result()` 抛出的非成功状态异常应继承同一个父类：

```java
public abstract class SubtaskException extends RuntimeException {
    protected SubtaskException(String message, Throwable cause) {
        super(message, cause);
    }
}

public final class SubtaskNotCompletedException extends SubtaskException { ... }
public final class SubtaskFailedException extends SubtaskException { ... }
public final class SubtaskCancelledException extends SubtaskException { ... }
```

调用方可以按需要 catch `SubtaskException`，也可以精确处理某一种状态。

## 11. 异常模型

项目规范要求业务异常统一，工具层异常建议放在 `com.smart.rag.exception` 或 `com.smart.rag.common.concurrent` 下，并最终可由 `GlobalExceptionHandler` 转换。

```java
public class ScopeExecutionException extends RuntimeException {

    private final List<Throwable> allFailures;

    public ScopeExecutionException(String scopeName, List<Throwable> failures) {
        super("并发作用域执行失败: " + scopeName, failures.get(0));
        this.allFailures = List.copyOf(failures);
        failures.stream()
            .skip(1)
            .forEach(this::addSuppressed);
    }

    public List<Throwable> allFailures() {
        return allFailures;
    }
}
```

建议异常：

| 异常 | 场景 |
|------|------|
| `ScopeExecutionException` | 子任务失败聚合 |
| `ScopeTimeoutException` | `joinUntil` 超时 |
| `ScopeClosedException` | 对已关闭 scope 调用 `fork` |
| `SubtaskNotCompletedException` | 在任务未成功时读取 `result()` |
| `SubtaskFailedException` | 对失败任务读取 `result()` |
| `SubtaskCancelledException` | 对已取消任务读取 `result()` |
| `ScopeViolationException` | 非 owner 线程调用 scope 生命周期方法 |

异常消息面向内部开发者；如果穿透到接口层，应由业务层转换为用户友好的 `BusinessException` 或统一错误响应。

失败聚合契约：

- `ScopeExecutionException.getCause()` 是首个失败，便于日志和默认异常链展示。
- `ScopeExecutionException.getSuppressed()` 包含除首个失败外的其它失败，便于保留 Java 标准异常语义。
- `ScopeExecutionException.allFailures()` 返回完整失败列表，便于 `COLLECT_ALL` 调用方做精细化处理。
- 取消不是失败；除非取消由超时触发，否则不进入 `allFailures()`。

## 12. 可观测性

`ObservedCallable` 负责记录任务级指标：

```java
final class ObservedCallable<T> implements Callable<T> {

    private final String scopeName;
    private final String taskName;
    private final Callable<T> delegate;
    private final SubtaskInternal<T> subtask;

    @Override
    public T call() throws Exception {
        long start = System.nanoTime();
        subtask.markRunning();
        try {
            T result = delegate.call();
            subtask.markSuccess(result, elapsed(start));
            return result;
        } catch (Throwable error) {
            subtask.markFailed(error, elapsed(start));
            throw error;
        }
    }
}
```

日志建议：

- `DEBUG`：任务开始、完成、取消。
- `WARN`：任务超时、任务取消失败、子任务失败但被策略吸收。
- `ERROR`：scope 级不可恢复失败。

不要在循环内打 INFO 以上日志；不要记录用户输入全文、Token、Cookie、完整 prompt 等敏感内容。

除了任务级观测，`DefaultTaskScope.close()` 还应记录 scope 级汇总，便于定位“哪个并发作用域慢、失败、取消多”：

```java
log.debug("TaskScope '{}' completed: total={}ms, tasks={}, success={}, failed={}, cancelled={}, slowestTask={}",
    name, scopeElapsedMillis, totalCount, successCount, failedCount, cancelledCount, slowestTaskName);
```

如果后续接入 Micrometer，指标建议按 scope 名称聚合：

- `task.scope.duration`
- `task.scope.tasks`
- `task.scope.failures`
- `task.scope.cancellations`
- `task.subtask.duration`

指标标签必须受控，`taskName` 和 `scopeName` 应来自代码常量，不允许直接使用用户输入。

## 13. 与 CompletableFuture 的关系

### 13.1 适合迁移

适合迁移的代码通常符合以下特征：

- 多个子任务属于同一个请求生命周期。
- 调用方需要等待这些子任务后才能继续。
- 任一必要任务失败时，其它任务继续执行没有意义。
- 需要传递 MDC / traceId / 安全上下文。
- 需要统一超时和取消。

示例：

```java
CompletableFuture<A> a = CompletableFuture.supplyAsync(this::loadA);
CompletableFuture<B> b = CompletableFuture.supplyAsync(this::loadB);

CompletableFuture.allOf(a, b).join();
return combine(a.join(), b.join());
```

可迁移为：

```java
try (TaskScope scope = scopedTasks.open("load-and-combine")) {
    Subtask<A> a = scope.fork("load-a", this::loadA);
    Subtask<B> b = scope.fork("load-b", this::loadB);

    scope.joinUntil(Duration.ofSeconds(3));
    scope.throwIfFailed();

    return combine(a.result(), b.result());
}
```

### 13.2 不适合迁移

- Spring 启动后的后台异步初始化。
- 不需要父调用方等待的 fire-and-forget 任务。
- Reactor 流式链路中依赖背压和订阅生命周期的任务。
- 已经由专用队列、调度器、消息系统管理生命周期的任务。

这些场景应继续使用现有模型，或单独设计后台任务管理器。

## 14. 与流式响应的边界

`executeStream`、SSE、Reactor `Flux` 等流式场景不能简单包进一个短生命周期 `TaskScope`。原因：

- 流式响应的生命周期由订阅方控制，不等同于方法调用返回。
- 取消信号来自 Reactor subscription，不一定等于 Java 线程中断。
- 背压、partial content 保存、usage 记录需要依赖 `doFinally` 等 Reactor 钩子。

因此 Phase 1 建议：

- 只在流式响应的前置准备阶段使用 `TaskScope`，例如并行加载配置、上下文、工具元数据。
- 不用 `TaskScope` 包住整个 `Flux` 生成过程。
- 如需深度整合 Reactor，应另开设计文档，明确 subscription 和 scope 的绑定关系。

## 15. 建议包结构

```text
src/main/java/com/smart/rag/common/concurrent/
├── ScopedTasks.java
├── TaskScope.java
├── Subtask.java
├── TaskState.java
├── ScopePolicy.java
├── ScopeOptions.java
├── ExecutorMode.java
├── DefaultScopedTasks.java
├── DefaultTaskScope.java
├── DefaultSubtask.java
├── ScopeExecutionException.java
├── ScopeTimeoutException.java
├── ScopeClosedException.java
├── ScopeViolationException.java
├── SubtaskException.java
├── SubtaskNotCompletedException.java
├── SubtaskFailedException.java
└── SubtaskCancelledException.java

src/main/java/com/smart/rag/common/concurrent/context/
├── ContextCarrier.java
├── ContextSnapshot.java
├── ContextRestorer.java
├── ContextAwareCallable.java
└── MdcContextCarrier.java

src/main/java/com/smart/rag/common/concurrent/policy/
├── ScopePolicyHandler.java
├── ShutdownOnFailurePolicy.java
└── CollectAllPolicy.java

src/main/java/com/smart/rag/common/concurrent/executor/
├── ScopeExecutorFactory.java
└── DefaultScopeExecutorFactory.java

src/main/java/com/smart/rag/config/
└── ScopedTaskAutoConfiguration.java
```

放在 `common/concurrent` 的原因：这是跨 RAG、ETL、模型刷新、沙箱执行都可能复用的基础设施，不属于某个业务模块。

Phase 3 才允许补充以下上下文 carrier，不能在 Phase 1 顺手加入：

```text
src/main/java/com/smart/rag/common/concurrent/context/
├── RequestContextCarrier.java
└── SecurityContextCarrier.java
```

Phase 4 才允许补充高级策略实现：

```text
src/main/java/com/smart/rag/common/concurrent/policy/
└── ShutdownOnSuccessPolicy.java
```

## 16. 设计模式总结

| 模式 | 应用位置 | 目的 |
|------|----------|------|
| 门面模式 | `ScopedTasks` | 给业务层提供简单入口，隐藏 executor、策略、上下文传递细节 |
| 装饰器模式 | `ContextAwareCallable`、`ObservedCallable` | 在不改业务任务的前提下增加上下文传播、状态记录、耗时统计 |
| 策略模式 | `ScopePolicyHandler` | 将失败/成功/收集语义从 scope 主流程中剥离 |
| 工厂模式 | `ScopeExecutorFactory` | 根据配置创建虚拟线程、平台线程池或共享 executor |
| 模板方法思想 | `DefaultTaskScope` 的 fork/join/close 生命周期 | 固定生命周期骨架，策略只定制关键节点行为 |
| 组合复用 | `DefaultTaskScope` 组合 executor、policy、context carriers | 避免继承膨胀，符合项目质量规范 |

## 17. Phase 实施范围

### 17.1 Phase 1 范围

Phase 1 只实现最小闭环，范围与第 4.1 节一致。任何未列入 Phase 1 的能力都不得在第一版实现中顺手加入。

交付物：

- `com.smart.rag.common.concurrent` 基础接口和默认实现。
- `com.smart.rag.common.concurrent.context` 的 MDC carrier。
- `com.smart.rag.common.concurrent.policy` 的 `SHUTDOWN_ON_FAILURE` / `COLLECT_ALL`。
- `ScopedTaskAutoConfiguration` 注册默认 `ScopedTasks`。
- 单元测试覆盖 Phase 1 语义。
- 一个小型测试服务或测试 fixture 验证集成行为。

Phase 1 明确不交付 SecurityContext / RequestContext carrier、`ShutdownOnSuccessPolicy`、Micrometer、共享 executor 配置属性。若实现过程中发现必须依赖这些能力，应先更新本文档和测试计划，再进入下一个 Phase。

### 17.2 Phase 2 范围

Phase 2 只做一个真实业务迁移，优先选择 `HybridSearchService` 或等价的请求内并发场景。

交付物：

- 迁移前行为表。
- 回归测试。
- 使用 `COLLECT_ALL` 保持部分降级语义。
- 迁移后删除对应位置的手写 `CompletableFuture` 编排。

### 17.3 Phase 3 范围

Phase 3 只处理复用后出现的基础设施扩展：

- Security / RequestContext carrier。
- 共享 executor / 平台线程池配置。
- `ScopedTaskProperties`。
- scope 级结构化日志增强。
- Micrometer 指标。
- 跨请求 bulkhead。

如果 Phase 3 引入 Security / RequestContext carrier，必须同步把 `ScopeOptions.inheritSecurityContext` / `inheritRequestContext` 的默认值、自动配置和泄漏测试补齐；在那之前默认值保持 `false`。

### 17.4 Phase 4 范围

Phase 4 才允许引入高级策略：

- `SHUTDOWN_ON_SUCCESS`。
- `PARTIAL_SUCCESS_OR_THROW`。
- quorum / race 类策略。
- `ScopeJoiner<R>`。
- Reactor 生命周期整合。

### 17.5 Phase 5 范围

Phase 5 只在 JDK 结构化并发转正并且项目 JVM 可升级后启动。

交付物：

- JDK 稳定 API 适配评估。
- 是否保留项目门面的决策。
- 迁移风险和兼容层方案。

## 18. Phase 验收测试

### 18.1 Phase 1 测试

- 所有任务成功时，`result()` 正确返回。
- 任一任务失败时，`SHUTDOWN_ON_FAILURE` 取消其它未完成任务。
- `COLLECT_ALL` 不因单个失败提前取消。
- `COLLECT_ALL` 存在未处理失败时，`close()` 记录安全网 WARN。
- `joinUntil` 超时后取消未完成任务。
- `joinUntil` 显式超时优先于 `ScopeOptions.defaultTimeout`。
- `join()` 在配置 `defaultTimeout` 时触发限时等待。
- `join()` / `joinUntil()` 只能成功调用一次，join 后再次 fork 抛出异常。
- join 内部等待任一 completion signal 后由 owner 线程调用策略。
- 不同 `ScopePolicyHandler.onTimeout()` 语义按策略生效。
- `close` 对未完成任务执行取消。
- `close` 等待已取消任务终止。
- `close` 超过 `closeTimeout` 时记录 WARN 并返回，不无限阻塞。
- `close` 幂等。
- 对已关闭 scope 调用 `fork` 抛出异常。
- 非 owner 线程调用 `fork` / `join` / `close` 抛出 `ScopeViolationException`。
- 未完成或失败任务调用 `result()` 抛出异常。
- `SubtaskNotCompletedException` / `SubtaskFailedException` / `SubtaskCancelledException` 都继承 `SubtaskException`。
- `result()` 不阻塞等待任务完成。
- MDC 在子任务中可见，任务结束后不污染执行线程。
- 多个失败任务通过 suppressed 聚合。
- `ScopeExecutionException.allFailures()` 返回完整失败列表。
- `Runnable` 重载能返回 `Subtask<Void>` 并正确传播异常。
- 嵌套 scope 中 parent 超时取消能通过中断传播到 inner scope。

### 18.2 Phase 2 测试

Phase 2 必须补业务回归测试：

- vector 成功、BM25 成功。
- vector 失败、BM25 成功。
- vector 成功、BM25 失败。
- vector 失败、BM25 失败。
- 整体超时。
- traceId 在迁移后的并发任务中保持一致。

### 18.3 Phase 3 测试

- SecurityContext / RequestContext 可传递且不泄漏。
- 共享 executor 不被 scope 关闭。
- `SHARED_EXECUTOR` 模式下 `close()` 只取消本 scope 任务，不关闭共享 executor。
- 配置属性覆盖默认 timeout / closeTimeout / executor mode。
- Micrometer 标签不包含用户输入。
- bulkhead 能限制跨请求并发。

### 18.4 Phase 4 测试

- `SHUTDOWN_ON_SUCCESS` 任一任务成功时取消其它未完成任务。
- `SHUTDOWN_ON_SUCCESS` 全部失败时聚合全部失败。
- `PARTIAL_SUCCESS_OR_THROW` 至少一个成功时保留部分结果，全部失败时抛异常。
- Reactor 整合时 cancellation 能从 subscription 传递到 scope。

### 18.5 Phase 5 验收

- JDK 稳定 API 适配方案经过 spike 验证。
- 保留项目门面或直接迁移 JDK API 的决策已记录。
- 现有业务测试在适配方案下保持通过。

## 19. Phase 迁移计划

### 19.1 候选顺序

建议按风险从低到高迁移：

1. 小型测试服务或新功能内部试点：用 `SHUTDOWN_ON_FAILURE` 验证 owner、timeout、取消、MDC、异常聚合等基础能力。
2. `HybridSearchService`：请求内两个检索分支并行，但当前语义是“部分降级”：vector 或 BM25 单分支失败时返回空列表并继续融合，只有两者都失败才抛 `BusinessException`。迁移时应使用 `COLLECT_ALL` 或新增 `PARTIAL_SUCCESS_OR_THROW` 策略，不能直接使用 `SHUTDOWN_ON_FAILURE`。
3. `ModelRegistryRefresher`：主要收益是复用 MDC 传递，但它是后台刷新任务，需谨慎判断是否适合 scope 生命周期。
4. `SandboxService`：已有虚拟线程和 MDC 手写逻辑，可用 context 装饰器减少重复。
5. `StandardStrategy` / `FastTrackStrategy`：ETL 多阶段并发较复杂，应等工具稳定后迁移。
6. `DatasetGenerator`：涉及自建并发上限，迁移前需确认性能和限流语义。

### 19.2 迁移规则

- 先加测试，再替换实现。
- 每次只迁移一个服务或一个方法。
- 不改变业务超时和异常对外表现，除非单独评审。
- 优先保留原有日志字段和 traceId。
- 不把长期后台任务强行放进请求 scope。
- 对现有降级语义先画出“单分支失败、全部失败、超时”的行为表，再选择或新增策略。

## 20. 风险和缓解

| 风险 | 说明 | 缓解 |
|------|------|------|
| 任务不响应中断 | `Future.cancel(true)` 只能发出中断 | 任务内部 IO、循环、sleep 必须检查中断或使用可中断 API |
| 异常语义变化 | `CompletableFuture` 常包裹 `CompletionException` | 迁移时保持外层服务异常契约，并补回归测试 |
| 上下文泄漏 | 线程复用时 ThreadLocal 未清理会污染后续任务 | `ContextRestorer.close()` 必须恢复 previous |
| 并发过高 | 虚拟线程降低线程成本，但不降低下游资源压力 | 对模型调用、数据库、外部 API 配置 `maxConcurrency` |
| 流式生命周期混淆 | Reactor subscription 生命周期不同于方法调用 | Phase 1 不包住完整 `Flux` |
| 过度抽象 | 一次性设计太多策略和上下文类型 | Phase 1 只实现最小闭环 |
| owner 约束被绕过 | scope 被传给其它线程继续 fork，破坏结构化边界 | 所有生命周期方法强制校验 owner 线程 |
| `COLLECT_ALL` 失败静默 | 调用方只等待不检查失败 | `close()` 安全网 WARN + 测试覆盖 |
| 共享 executor 被误关闭 | `SHARED_EXECUTOR` close 时影响其它业务 | 显式 `executorOwnedByScope` 标记 |
| close 返回过早 | 子任务 cancel 后仍继续运行 | `close()` 等待任务终止，并记录取消失败 |
| close 无限阻塞 | 子任务吞掉中断或卡在不可中断 IO | `ScopeOptions.closeTimeout` 提供硬上限 |
| join 实现选型错误 | allOf/逐个 get 无法表达 fail-fast 或正确并发等待 | 使用 completion signal + anyOf，由 owner 线程驱动策略 |
| JDK 21 虚拟线程 pinning | synchronized 内阻塞会固定 carrier 线程 | 阻塞等待路径使用 ReentrantLock/并发集合/原子类，避免 synchronized 包裹阻塞 |
| 降级语义被 fail-fast 破坏 | 直接迁移 HybridSearchService 会改变 partial result 行为 | 对降级链路使用 COLLECT_ALL 或新增 partial-success 策略 |

## 21. 结论

基于 JDK 21 稳定 API 实现项目内结构化并发工具是可行的。核心不是复制 JDK 预览 API 的类名，而是把结构化并发的关键语义落到项目基础设施：

- `TaskScope` 管生命周期。
- `Subtask` 管子任务结果。
- `ScopePolicyHandler` 管成功/失败策略。
- `ContextAwareCallable` 管 ThreadLocal 类上下文传递。
- `ScopeExecutorFactory` 管虚拟线程和线程池选择。

Phase 1 应小步实现，并优先在测试服务中锁定并发、失败、取消、超时和上下文传递语义。等工具的核心语义被测试锁住后，再进入 Phase 2 迁移真实业务。

## 22. JEP 演进参考与本项目取舍

| JEP 演进点 | 本项目采纳方式 | 不直接采纳原因 |
|------------|----------------|----------------|
| owner 线程约束 | 强制采纳，非 owner 调用抛 `ScopeViolationException` | 这是结构化生命周期的核心，不依赖预览 API |
| Joiner / result 策略一体化 | 暂不实现，只保留 `ScopePolicyHandler` | 当前项目 Phase 1 不需要强类型策略结果，避免过度抽象 |
| timeout 由策略决定 | 采纳为 `ScopePolicyHandler.onTimeout()` | 不使用预览 Joiner API，但保留其语义 |
| `Subtask.get()` 命名争议 | 使用 `result()` 并声明非阻塞契约 | 避免与 `Future.get()` 混淆 |
| `awaitAll()` 移除 | `COLLECT_ALL` 增加失败处理安全网 | 项目仍需要批量收集，但必须防止失败静默 |
| `fork(Runnable)` | 采纳为 default 重载 | 简化无返回值任务调用 |
| ScopedValue 继承 | Phase 1 不实现 | 项目当前主要痛点是 MDC / ThreadLocal 类上下文，且不能依赖预览特性 |

本项目的原则是：吸收 JEP 演进里已经稳定下来的语义约束，但不绑定仍在变化的预览 API 形状。这样未来 JDK 结构化并发转正后，可以通过适配层迁移，而不是让业务代码直接依赖预览类名。

## 23. 参考资料

| 资料 | 本文档使用方式 |
|------|----------------|
| [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) | 确认 JDK 21 虚拟线程是稳定能力，可作为 executor 基础 |
| [JDK 21 Virtual Threads Guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) | 确认 JDK 21 下 synchronized + 阻塞操作存在 pinning 风险，以及 ReentrantLock 替代建议 |
| [JEP 505: Structured Concurrency (Fifth Preview)](https://openjdk.org/jeps/505) | 参考 owner 线程、Subtask 结果读取、Joiner 语义和取消边界 |
| [JEP 525: Structured Concurrency (Sixth Preview)](https://openjdk.org/jeps/525) | 参考 timeout 进入完成策略、成功/失败策略命名和结果收集演进 |
| [JEP 533: Structured Concurrency (Seventh Preview)](https://openjdk.org/jeps/533) | 参考 typed result/exception、timeout 处理和等待全部 API 的风险取舍 |
| [JDK 25 StructuredTaskScope Preview API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html) | 参考 owner 线程、fork/join/close 生命周期、close 等待子任务终止、fork-after-join 禁止等结构化约束 |
| [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) | 确认 synchronized pinning 的改善属于 JDK 24，不适合作为本项目 JDK 21 的默认假设 |
