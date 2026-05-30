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
| `HybridSearchService` | `CompletableFuture.supplyAsync()` 并行向量/BM25 检索 | 失败、取消、上下文传播分散 |
| `ModelRegistryRefresher` | `CompletableFuture` + 手写 MDC 恢复 | MDC 传递重复实现 |
| `SandboxService` | `ExecutorService` + 手写 MDC 恢复 | 作用域生命周期不统一 |
| `StandardStrategy` / `FastTrackStrategy` | 多阶段 `CompletableFuture.allOf()` | 异常聚合和取消语义不直观 |
| `DatasetGenerator` | 自建线程池 + `CompletableFuture` | 并发上限、关闭、异常传播需要统一 |

这些场景并不都应该立即迁移，但它们说明项目需要一个统一的“请求内并发编排”抽象，减少重复代码和语义漂移。

## 2. 设计目标

### 2.1 目标

- 提供类似结构化并发的作用域 API：所有子任务必须挂在父作用域下。
- 父作用域退出时，自动取消未完成任务。
- 支持等待全部、超时等待、失败即取消、成功即取消、全部收集等策略。
- 支持 MDC、请求上下文、安全上下文等 ThreadLocal 类上下文的显式传递。
- 支持任务命名、耗时记录、失败聚合和可观测性。
- 只使用 JDK 21 稳定 API 和项目已有 Spring / SLF4J 能力。
- 不新增第三方依赖。

### 2.2 非目标

- 不承诺 100% 兼容 JDK 预览 `StructuredTaskScope` API。
- 不通过反射读取或修改 JVM 内部 `ThreadLocalMap`。
- 不强制杀死不响应中断的任务。
- 不替代所有 `CompletableFuture`。长期后台任务、事件驱动链路、缓存预热、无父请求生命周期的异步任务仍可继续使用现有模型。
- 不在第一阶段接管 Reactor `Flux` 的完整流式生命周期。

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

## 4. 核心抽象

### 4.1 ScopedTasks

`ScopedTasks` 是业务入口门面，负责创建 `TaskScope`。

```java
public interface ScopedTasks {

    TaskScope open(String name);

    TaskScope open(String name, ScopePolicy policy);

    TaskScope open(String name, ScopeOptions options);
}
```

推荐实现类：

```text
com.smart.rag.common.concurrent.DefaultScopedTasks
```

### 4.2 TaskScope

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

### 4.3 Subtask

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

### 4.4 ScopeOptions

```java
public record ScopeOptions(
    String name,
    ScopePolicy policy,
    ExecutorMode executorMode,
    int maxConcurrency,
    Duration defaultTimeout,
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
            true,
            true,
            true,
            true
        );
    }
}
```

`maxConcurrency = 0` 表示不在 scope 层限流，由调用方或底层资源控制。对外部模型调用、RAG 检索、工具调用等场景，可配置正数并发上限。

`defaultTimeout` 的优先级：

1. 调用 `joinUntil(Duration)` 时，以该显式超时为准。
2. 调用 `join()` 且 `defaultTimeout > 0` 时，按 `defaultTimeout` 限时等待。
3. 调用 `join()` 且 `defaultTimeout == Duration.ZERO` 时，无限等待直到所有任务完成或策略提前终止。

`executorOwnedByScope` 区分 executor 生命周期：

- `true`：executor 由本 scope 创建，`close()` 时可以关闭。
- `false`：executor 是 Spring 或外部共享资源，`close()` 只能取消本 scope 的任务，不能关闭 executor。

## 5. 策略语义

### 5.1 ScopePolicy

```java
public enum ScopePolicy {
    SHUTDOWN_ON_FAILURE,
    SHUTDOWN_ON_SUCCESS,
    COLLECT_ALL
}
```

| 策略 | 语义 | 适用场景 |
|------|------|----------|
| `SHUTDOWN_ON_FAILURE` | 任一任务失败后取消其它未完成任务 | 请求内多个必要子任务并行 |
| `SHUTDOWN_ON_SUCCESS` | 任一任务成功后取消其它未完成任务 | 多数据源竞速、fallback 竞速 |
| `COLLECT_ALL` | 等待全部结束，不自动 fail-fast | 批量评估、ETL 多候选处理 |

第一阶段建议默认使用 `SHUTDOWN_ON_FAILURE`，因为 RAG 请求内并行子任务通常是共同构成最终结果的必要步骤。

`COLLECT_ALL` 不是“忽略失败”。它只是不在第一个失败时取消其它任务。调用方必须显式检查每个 `Subtask`，或调用 `throwIfFailed()` 统一抛出失败。为了避免失败静默，`DefaultTaskScope.close()` 必须在 `COLLECT_ALL` 模式下检查未处理失败；如果发现调用方既没有调用 `throwIfFailed()`，也没有读取失败子任务的 `exception()`，应记录 `WARN`：

```java
log.warn("TaskScope '{}' closed with {} unhandled failure(s). "
        + "Call throwIfFailed() or inspect subtask.exception() explicitly.",
    name, unhandledFailureCount);
```

`SHUTDOWN_ON_SUCCESS` 的完整语义：

| 条件 | `throwIfFailed()` | 成功结果读取 |
|------|-------------------|--------------|
| 任一任务成功 | 不抛异常，其它未完成任务被取消 | 读取成功的 `Subtask.result()` |
| 全部任务失败 | 抛出 `ScopeExecutionException`，包含全部失败 | 所有失败任务 `result()` 抛 `SubtaskFailedException` |
| 超时且已有成功 | 不抛异常，取消未完成任务 | 读取已成功任务的结果 |
| 超时且无成功 | 抛出 `ScopeTimeoutException`，包含已知失败 | 未完成任务 `result()` 抛 `SubtaskCancelledException` 或 `SubtaskNotCompletedException` |

### 5.2 策略模式

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
ShutdownOnSuccessPolicy
CollectAllPolicy
```

后续如果出现“至少 N 个任务成功即可返回”的需求，可以新增 `QuorumSuccessPolicy`，而不修改 `DefaultTaskScope` 主流程。

`onTimeout()` 是策略的一部分，而不是 `joinUntil()` 的硬编码分支。不同策略的超时含义不同：

| 策略 | 超时语义 |
|------|----------|
| `SHUTDOWN_ON_FAILURE` | 超时视为 scope 失败，取消未完成任务并抛出 `ScopeTimeoutException` |
| `SHUTDOWN_ON_SUCCESS` | 如果已有成功结果，则取消未完成任务并允许读取成功结果；否则抛出 `ScopeTimeoutException` |
| `COLLECT_ALL` | 取消未完成任务，保留已完成结果和已知失败，调用方继续按收集结果处理 |

如果未来需要让策略直接返回强类型结果，可以在当前 `ScopePolicyHandler` 之外新增 `ScopeJoiner<R>` 抽象，借鉴 JEP Joiner 的“策略与结果收集一体化”设计。但第一阶段不引入该复杂度。

## 6. 上下文传递设计

### 6.1 ContextCarrier

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

### 6.2 MDC 示例

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

### 6.3 ContextSnapshot

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

### 6.4 装饰器模式

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

## 7. Executor 设计

### 7.1 ExecutorMode

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

### 7.2 工厂模式

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

## 8. TaskScope 执行流程

### 8.1 fork

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

### 8.2 join

```text
1. 校验当前线程是 owner。
2. 读取当前 scope 已注册任务快照。
3. 如果 defaultTimeout > 0，则转换为限时等待；否则无限等待。
4. 每个任务完成后更新状态。
5. 成功任务记录 result。
6. 失败任务记录 Throwable。
7. 调用 ScopePolicyHandler 判断是否需要取消其它任务。
8. 如果策略要求提前停止，则 cancel 未完成任务。
```

### 8.3 joinUntil

```text
1. 校验当前线程是 owner。
2. 计算 deadline。
3. 对所有任务按剩余时间等待。
4. 任务完成时仍按 ScopePolicyHandler 处理 success/failure。
5. 超时后取消未完成任务。
6. 调用 ScopePolicyHandler.onTimeout(state) 决定抛异常还是保留部分结果。
7. 保留已失败任务作为 suppressed，便于排查。
```

### 8.4 close

```text
1. 校验当前线程是 owner。
2. 标记 scope closing。
3. 取消所有 NEW/RUNNING 状态任务。
4. 等待已取消任务结束，确保 close 返回后没有子任务仍在运行。
5. 如果 policy == COLLECT_ALL 且存在未处理失败，记录 WARN。
6. 记录 scope 级汇总日志。
7. 如果 executor 由 scope 创建，则 shutdown。
8. 清理内部任务列表和上下文引用。
9. 多次 close 必须幂等。
```

`close()` 必须尽力等待任务终止，而不是只调用 `cancel(true)` 后立即返回。否则父作用域已经退出，子任务仍可能继续运行，结构化生命周期保证会失效。等待终止的最大时间可以复用剩余 timeout，或由 `ScopeOptions.closeTimeout` 在后续阶段补充。

## 9. 异常模型

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

## 10. 可观测性

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

## 11. 与 CompletableFuture 的关系

### 11.1 适合迁移

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

### 11.2 不适合迁移

- Spring 启动后的后台异步初始化。
- 不需要父调用方等待的 fire-and-forget 任务。
- Reactor 流式链路中依赖背压和订阅生命周期的任务。
- 已经由专用队列、调度器、消息系统管理生命周期的任务。

这些场景应继续使用现有模型，或单独设计后台任务管理器。

## 12. 与流式响应的边界

`executeStream`、SSE、Reactor `Flux` 等流式场景不能简单包进一个短生命周期 `TaskScope`。原因：

- 流式响应的生命周期由订阅方控制，不等同于方法调用返回。
- 取消信号来自 Reactor subscription，不一定等于 Java 线程中断。
- 背压、partial content 保存、usage 记录需要依赖 `doFinally` 等 Reactor 钩子。

因此第一阶段建议：

- 只在流式响应的前置准备阶段使用 `TaskScope`，例如并行加载配置、上下文、工具元数据。
- 不用 `TaskScope` 包住整个 `Flux` 生成过程。
- 如需深度整合 Reactor，应另开设计文档，明确 subscription 和 scope 的绑定关系。

## 13. 建议包结构

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
├── SubtaskNotCompletedException.java
├── SubtaskFailedException.java
└── SubtaskCancelledException.java

src/main/java/com/smart/rag/common/concurrent/context/
├── ContextCarrier.java
├── ContextSnapshot.java
├── ContextRestorer.java
├── ContextAwareCallable.java
├── MdcContextCarrier.java
├── RequestContextCarrier.java
└── SecurityContextCarrier.java

src/main/java/com/smart/rag/common/concurrent/policy/
├── ScopePolicyHandler.java
├── ShutdownOnFailurePolicy.java
├── ShutdownOnSuccessPolicy.java
└── CollectAllPolicy.java

src/main/java/com/smart/rag/common/concurrent/executor/
├── ScopeExecutorFactory.java
└── DefaultScopeExecutorFactory.java

src/main/java/com/smart/rag/config/
└── ScopedTaskAutoConfiguration.java
```

放在 `common/concurrent` 的原因：这是跨 RAG、ETL、模型刷新、沙箱执行都可能复用的基础设施，不属于某个业务模块。

## 14. 设计模式总结

| 模式 | 应用位置 | 目的 |
|------|----------|------|
| 门面模式 | `ScopedTasks` | 给业务层提供简单入口，隐藏 executor、策略、上下文传递细节 |
| 装饰器模式 | `ContextAwareCallable`、`ObservedCallable` | 在不改业务任务的前提下增加上下文传播、状态记录、耗时统计 |
| 策略模式 | `ScopePolicyHandler` | 将失败/成功/收集语义从 scope 主流程中剥离 |
| 工厂模式 | `ScopeExecutorFactory` | 根据配置创建虚拟线程、平台线程池或共享 executor |
| 模板方法思想 | `DefaultTaskScope` 的 fork/join/close 生命周期 | 固定生命周期骨架，策略只定制关键节点行为 |
| 组合复用 | `DefaultTaskScope` 组合 executor、policy、context carriers | 避免继承膨胀，符合项目质量规范 |

## 15. 第一阶段实现范围

建议第一阶段只实现最小闭环：

- `ScopedTasks.open(String name)`
- `SHUTDOWN_ON_FAILURE`
- `COLLECT_ALL`
- `join()` / `joinUntil(Duration)`
- `throwIfFailed()`
- `try-with-resources` 自动取消
- MDC 上下文传递
- 虚拟线程 executor
- 单元测试覆盖核心语义

暂缓：

- `SHUTDOWN_ON_SUCCESS`
- Spring Security 上下文传递
- RequestContextHolder 传递
- Micrometer 指标
- 与 Reactor 流式深度整合
- 自动迁移所有 `CompletableFuture`

## 16. 测试计划

### 16.1 单元测试

第一阶段必须覆盖：

- 所有任务成功时，`result()` 正确返回。
- 任一任务失败时，`SHUTDOWN_ON_FAILURE` 取消其它未完成任务。
- `COLLECT_ALL` 不因单个失败提前取消。
- `COLLECT_ALL` 存在未处理失败时，`close()` 记录安全网 WARN。
- `joinUntil` 超时后取消未完成任务。
- `joinUntil` 显式超时优先于 `ScopeOptions.defaultTimeout`。
- `join()` 在配置 `defaultTimeout` 时触发限时等待。
- 不同 `ScopePolicyHandler.onTimeout()` 语义按策略生效。
- `close` 对未完成任务执行取消。
- `close` 等待已取消任务终止。
- `close` 幂等。
- 对已关闭 scope 调用 `fork` 抛出异常。
- 非 owner 线程调用 `fork` / `join` / `close` 抛出 `ScopeViolationException`。
- 未完成或失败任务调用 `result()` 抛出异常。
- `result()` 不阻塞等待任务完成。
- MDC 在子任务中可见，任务结束后不污染执行线程。
- 多个失败任务通过 suppressed 聚合。
- `ScopeExecutionException.allFailures()` 返回完整失败列表。
- `SHARED_EXECUTOR` 模式下 `close()` 不关闭共享 executor。
- `Runnable` 重载能返回 `Subtask<Void>` 并正确传播异常。

实现 `SHUTDOWN_ON_SUCCESS` 前必须补充：

- 任一任务成功时取消其它未完成任务。
- 全部任务失败时，`throwIfFailed()` 聚合全部失败。
- 超时且已有成功时允许读取成功结果。
- 超时且无成功时抛出 `ScopeTimeoutException`。

### 16.2 集成测试

第一批集成测试建议围绕 `HybridSearchService` 或一个小型测试服务：

- 并行任务成功合并结果。
- 一个检索分支失败时另一个分支被取消。
- traceId 在并行任务日志中保持一致。
- 超时配置生效。

### 16.3 回归测试

迁移每一个现有 `CompletableFuture` 使用点时，都应先补齐当前行为测试，再替换实现。尤其注意：

- 异常类型是否改变。
- 超时时间是否改变。
- 是否从“等待全部”变成“失败即取消”。
- 是否影响调用方对 partial result 的处理。

## 17. 迁移策略

### 17.1 候选顺序

建议按风险从低到高迁移：

1. `HybridSearchService`：请求内两个检索分支并行，最贴近结构化并发模型。
2. `ModelRegistryRefresher`：主要收益是复用 MDC 传递，但它是后台刷新任务，需谨慎判断是否适合 scope 生命周期。
3. `SandboxService`：已有虚拟线程和 MDC 手写逻辑，可用 context 装饰器减少重复。
4. `StandardStrategy` / `FastTrackStrategy`：ETL 多阶段并发较复杂，应等工具稳定后迁移。
5. `DatasetGenerator`：涉及自建并发上限，迁移前需确认性能和限流语义。

### 17.2 迁移规则

- 先加测试，再替换实现。
- 每次只迁移一个服务或一个方法。
- 不改变业务超时和异常对外表现，除非单独评审。
- 优先保留原有日志字段和 traceId。
- 不把长期后台任务强行放进请求 scope。

## 18. 风险和缓解

| 风险 | 说明 | 缓解 |
|------|------|------|
| 任务不响应中断 | `Future.cancel(true)` 只能发出中断 | 任务内部 IO、循环、sleep 必须检查中断或使用可中断 API |
| 异常语义变化 | `CompletableFuture` 常包裹 `CompletionException` | 迁移时保持外层服务异常契约，并补回归测试 |
| 上下文泄漏 | 线程复用时 ThreadLocal 未清理会污染后续任务 | `ContextRestorer.close()` 必须恢复 previous |
| 并发过高 | 虚拟线程降低线程成本，但不降低下游资源压力 | 对模型调用、数据库、外部 API 配置 `maxConcurrency` |
| 流式生命周期混淆 | Reactor subscription 生命周期不同于方法调用 | 第一阶段不包住完整 `Flux` |
| 过度抽象 | 一次性设计太多策略和上下文类型 | 第一阶段只实现最小闭环 |
| owner 约束被绕过 | scope 被传给其它线程继续 fork，破坏结构化边界 | 所有生命周期方法强制校验 owner 线程 |
| `COLLECT_ALL` 失败静默 | 调用方只等待不检查失败 | `close()` 安全网 WARN + 测试覆盖 |
| 共享 executor 被误关闭 | `SHARED_EXECUTOR` close 时影响其它业务 | 显式 `executorOwnedByScope` 标记 |
| close 返回过早 | 子任务 cancel 后仍继续运行 | `close()` 等待任务终止，并记录取消失败 |

## 19. 结论

基于 JDK 21 稳定 API 实现项目内结构化并发工具是可行的。核心不是复制 JDK 预览 API 的类名，而是把结构化并发的关键语义落到项目基础设施：

- `TaskScope` 管生命周期。
- `Subtask` 管子任务结果。
- `ScopePolicyHandler` 管成功/失败策略。
- `ContextAwareCallable` 管 ThreadLocal 类上下文传递。
- `ScopeExecutorFactory` 管虚拟线程和线程池选择。

第一阶段应小步实现，并优先在请求内并发、失败语义清晰的场景试点。等工具的取消、超时、异常聚合和上下文传递都被测试锁住后，再逐步替换散落的 `CompletableFuture` 编排代码。

## 20. JEP 演进参考与本项目取舍

| JEP 演进点 | 本项目采纳方式 | 不直接采纳原因 |
|------------|----------------|----------------|
| owner 线程约束 | 强制采纳，非 owner 调用抛 `ScopeViolationException` | 这是结构化生命周期的核心，不依赖预览 API |
| Joiner / result 策略一体化 | 暂不实现，只保留 `ScopePolicyHandler` | 当前项目第一阶段不需要强类型策略结果，避免过度抽象 |
| timeout 由策略决定 | 采纳为 `ScopePolicyHandler.onTimeout()` | 不使用预览 Joiner API，但保留其语义 |
| `Subtask.get()` 命名争议 | 使用 `result()` 并声明非阻塞契约 | 避免与 `Future.get()` 混淆 |
| `awaitAll()` 移除 | `COLLECT_ALL` 增加失败处理安全网 | 项目仍需要批量收集，但必须防止失败静默 |
| `fork(Runnable)` | 采纳为 default 重载 | 简化无返回值任务调用 |
| ScopedValue 继承 | 第一阶段不实现 | 项目当前主要痛点是 MDC / ThreadLocal 类上下文，且不能依赖预览特性 |

本项目的原则是：吸收 JEP 演进里已经稳定下来的语义约束，但不绑定仍在变化的预览 API 形状。这样未来 JDK 结构化并发转正后，可以通过适配层迁移，而不是让业务代码直接依赖预览类名。
