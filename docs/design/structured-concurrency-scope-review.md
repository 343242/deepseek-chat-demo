# 结构化并发设计文档审查报告

> 审查对象：[structured-concurrency-scope.md](./structured-concurrency-scope.md)
>
> 审查方法：以 JEP 505/525/533 演进中沉淀的核心语义为参考，审查原设计在"抽取结构化并发有价值语义"这一目标下的完整性和内部一致性
>
> 审查日期：2026-05-31

## 1. 审查框架

原设计明确声明了自己的定位：

> 该工具不是直接复刻 JDK 预览 API，而是抽取结构化并发对本项目有价值的语义：父子任务生命周期、统一等待、失败传播、自动取消、上下文传递和可观测性。

因此本审查**不以"是否对齐 JDK API"为标准**，而是围绕以下问题：

1. 声明的六个核心语义是否被完整覆盖？
2. 各抽象之间的契约是否自洽？
3. JEP 演进中被反复强调的"不可妥协的约束"，原设计是否遗漏？
4. 实际使用中会遇到什么问题？

## 2. 核心语义覆盖度

原设计声明了六个核心语义，逐一审查：

| 语义 | 覆盖状态 | 说明 |
|------|----------|------|
| 父子任务生命周期 | ✅ 完整 | `try-with-resources` + `close()` 幂等 + 取消未完成任务 |
| 统一等待 | ✅ 完整 | `join()` + `joinUntil(Duration)` |
| 失败传播 | ⚠️ 部分 | `SHUTDOWN_ON_FAILURE` 定义清晰，但 `COLLECT_ALL` 的失败处理有漏洞 |
| 自动取消 | ✅ 完整 | scope close 时取消 + 策略触发取消 |
| 上下文传递 | ✅ 完整 | `ContextCarrier` + 装饰器，JDK 21 约束下的正确方案 |
| 可观测性 | ⚠️ 部分 | `ObservedCallable` 记录指标，但缺少结构化的作用域级视图 |

## 3. 发现项

### 3.1 [HIGH] COLLECT_ALL 策略的失败静默风险

**原设计：**
```
COLLECT_ALL — 等待全部结束，不自动 fail-fast
```

**问题：**

`COLLECT_ALL` 的语义是"收集所有结果"，但文档未定义失败结果的处理路径：

1. `throwIfFailed()` 是可选调用 — 如果调用方忘记调用，失败被静默吞掉
2. `close()` 不会检查未处理的失败 — scope 干净关闭，失败无痕消失
3. JEP 演进中（533）专门移除了 `awaitAll()` Joiner，理由正是：纯粹的"等待全部"不提供失败语义，容易导致错误被忽略

这与原设计在第 2.1 节声明的"失败不会被隐藏在异步链尾部"目标矛盾。

**建议：**

在 `close()` 中增加安全机制：

```java
// DefaultTaskScope.close()
@Override
public void close() {
    if (closed) return;
    closed = true;

    cancelUnfinished();

    // 安全网：COLLECT_ALL 模式下，如果有未处理的失败，记录 WARN
    if (policy == ScopePolicy.COLLECT_ALL && hasUnhandledFailures()) {
        log.warn("TaskScope '{}' closed with {} unhandled failure(s). "
                + "Call throwIfFailed() or check subtask exceptions explicitly.",
                name, unhandledFailureCount());
    }

    releaseResources();
}
```

或者将枚举改名为 `COLLECT_ALL_AND_CHECK`，在语义上强调需要显式处理失败。

### 3.2 [HIGH] 超时语义不完整 — joinUntil 与 ScopeOptions.defaultTimeout 的关系

**原设计同时提供了两种超时配置：**

```java
// 方式 1：通过 ScopeOptions
ScopeOptions options = new ScopeOptions(
    name, policy, executorMode, maxConcurrency,
    Duration.ofSeconds(5),  // defaultTimeout
    ...
);

// 方式 2：通过 joinUntil
scope.joinUntil(Duration.ofSeconds(3));
```

**问题：**

文档未说明：

1. 如果同时配置了 `defaultTimeout` 和调用了 `joinUntil()`，哪个生效？
2. 如果只配置了 `defaultTimeout` 但调用的是无参 `join()`，超时是否生效？
3. 超时后的行为由谁决定 — `ScopePolicyHandler`？`close()`？还是 `joinUntil()` 本身？

**建议：**

明确优先级和行为：

```
超时生效规则：
1. joinUntil(Duration) 显式指定超时 → 使用该超时
2. 仅调用 join()，但 ScopeOptions.defaultTimeout > 0 → 使用 defaultTimeout
3. 仅调用 join()，且 defaultTimeout 为 0 → 无限等待

超时后行为：
1. 取消所有未完成任务
2. 抛出 ScopeTimeoutException（包含已完成任务的结果作为 suppressed）
3. close() 正常清理
```

### 3.3 [HIGH] ScopePolicyHandler 缺少超时回调

**原设计：**

```java
interface ScopePolicyHandler {
    void onSuccess(SubtaskInternal<?> task, ScopeState state);
    void onFailure(SubtaskInternal<?> task, Throwable error, ScopeState state);
    boolean shouldStop(ScopeState state);
}
```

**问题：**

JEP 演进中（525/533）反复强调：超时后的行为应由策略决定。JEP 525 增加了 `onTimeout()`，JEP 533 将其改为 `timeout()` 方法，允许 Joiner 在超时时返回部分结果或抛出特定异常。

原设计的 `ScopePolicyHandler` 没有超时回调：

- `SHUTDOWN_ON_FAILURE` 超时后应该抛异常
- `SHUTDOWN_ON_SUCCESS` 超时后如果有已成功的结果，应该返回该结果
- `COLLECT_ALL` 超时后应该返回已收集的部分结果

当前设计无法表达这些差异。

**建议：**

增加超时回调：

```java
interface ScopePolicyHandler {
    void onSuccess(SubtaskInternal<?> task, ScopeState state);
    void onFailure(SubtaskInternal<?> task, Throwable error, ScopeState state);
    void onTimeout(ScopeState state);  // 新增
    boolean shouldStop(ScopeState state);
}
```

各策略实现：

```java
// ShutdownOnFailurePolicy
@Override
public void onTimeout(ScopeState state) {
    // 超时 = 失败，保留已失败任务作为 suppressed
    throw new ScopeTimeoutException(scopeName, state.failures());
}

// CollectAllPolicy
@Override
public void onTimeout(ScopeState state) {
    // 超时后不抛异常，由调用方检查 partial results
    log.warn("Scope '{}' timed out with {}/{} tasks completed",
        scopeName, state.successCount(), state.totalCount());
}
```

### 3.4 [MEDIUM] Subtask.result() 契约不明确

**原设计：**

```java
public interface Subtask<T> {
    T result();
    Throwable exception();
    boolean cancel();
}
```

**问题：**

文档未明确 `result()` 的调用契约：

1. 是否阻塞？（应该是非阻塞 — 结构化并发要求先 join 再取结果）
2. 在 `join()` 之前调用会怎样？
3. 任务失败时调用 `result()` 抛什么异常？
4. 任务被取消时调用 `result()` 抛什么异常？

JEP 505 的批评文章（SoftwareMill）专门指出了 `Subtask.get()` 命名与 `Future.get()` 语义不同导致的混淆。原设计用 `result()` 避免了命名混淆，但缺少契约说明。

**建议：**

```java
/**
 * 获取子任务的执行结果。
 *
 * <p>此方法为非阻塞操作，必须在 {@link TaskScope#join()} 或
 * {@link TaskScope#joinUntil(Duration)} 之后调用。
 *
 * @return 任务的执行结果
 * @throws SubtaskNotCompletedException 如果任务尚未完成（未调用 join 或任务仍在运行）
 * @throws SubtaskFailedException 如果任务执行失败（可通过 {@link #exception()} 获取原因）
 * @throws SubtaskCancelledException 如果任务被取消
 */
T result();
```

### 3.5 [MEDIUM] SHUTDOWN_ON_SUCCESS 的"全部失败"场景未定义

**原设计：**

```
SHUTDOWN_ON_SUCCESS — 任一任务成功后取消其它未完成任务
```

**问题：**

这个命名和描述只覆盖了"成功"场景，未定义：

- 如果所有任务都失败了，怎么办？
- 调用 `throwIfFailed()` 时抛出什么？
- 调用 `result()` 时抛出什么？

对比 JEP 505 的 `anySuccessfulOrThrow()`，命名本身就包含了"全部失败则抛异常"的语义。

**建议：**

补充完整语义表：

| 条件 | throwIfFailed() | result() |
|------|-----------------|----------|
| 任一任务成功 | 不抛异常 | 返回成功任务的结果 |
| 全部任务失败 | 抛出 ScopeExecutionException（包含所有失败） | 抛出 SubtaskNotCompletedException |
| 超时且已有成功 | 不抛异常 | 返回已成功任务的结果 |
| 超时且无成功 | 抛出 ScopeTimeoutException | 抛出 SubtaskNotCompletedException |

### 3.6 [MEDIUM] 缺少作用域所有权约束的明确声明

**原设计：**

文档在 4.2 节的契约中写了：

> `fork()` 只能在 scope 未关闭时调用。

但未说明：是否只有创建 scope 的线程才能调用 `fork()` / `join()`？

JEP 505/525/533 均强制 owner 线程约束 — 非 owner 调用 fork 会抛异常。这是结构化并发"代码结构决定线程结构"原则的体现。

**问题：**

如果不明确这一点，可能出现：

```java
TaskScope scope = scopedTasks.open("parent");

// 在另一个线程中 fork — 这允许吗？
otherThread.submit(() -> {
    scope.fork("task", () -> doWork());
});
```

这种用法破坏了"父子任务生命周期绑定到代码块"的结构化保证。

**建议：**

二选一，但必须明确：

**选项 A：强制 owner（推荐，与结构化并发原则一致）**
```java
// DefaultTaskScope.fork()
@Override
public <T> Subtask<T> fork(String name, Callable<T> task) {
    if (Thread.currentThread() != ownerThread) {
        throw new ScopeViolationException(
            "fork() must be called from the scope's owner thread");
    }
    // ...
}
```

**选项 B：不强制（放宽限制）**
在文档中明确记录：
> 本工具不强制 owner 线程约束。调用方需自行保证线程安全，scope 内部通过 `synchronized` 保护共享状态。

### 3.7 [MEDIUM] 异常聚合模型缺少"首个失败"和"全部失败"的区分

**原设计：**

```java
public class ScopeExecutionException extends RuntimeException {
    public ScopeExecutionException(String scopeName, Throwable firstFailure, List<Throwable> failures) {
        super("并发作用域执行失败: " + scopeName, firstFailure);
        failures.stream()
            .filter(failure -> failure != firstFailure)
            .forEach(this::addSuppressed);
    }
}
```

**问题：**

`firstFailure` 作为 cause，其余作为 suppressed — 这在大多数场景下足够。但：

1. `SHUTDOWN_ON_FAILURE` 场景：只有一个失败（首个），其余被取消 — OK
2. `COLLECT_ALL` 场景：可能有多个独立失败 — 调用方可能需要遍历所有失败
3. 没有提供 `failures()` 方法获取完整失败列表 — 只能通过 `getSuppressed()` + `getCause()` 手动组装

**建议：**

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

    /** 获取所有失败（包含 cause） */
    public List<Throwable> allFailures() {
        return allFailures;
    }
}
```

### 3.8 [LOW] 缺少 fork 的 Runnable 重载

**原设计：** 只有 `fork(String name, Callable<T> task)`

**问题：**

不需要返回值的副作用任务（如日志记录、指标上报、缓存预热）被迫写成：

```java
scope.fork("log-metrics", () -> {
    logMetrics();
    return null;
});
```

**建议：**

增加重载：

```java
default Subtask<Void> fork(String name, Runnable task) {
    return fork(name, () -> {
        task.run();
        return null;
    });
}
```

### 3.9 [LOW] ExecutorMode.SHARED_EXECUTOR 的生命周期管理

**原设计：**

```java
public enum ExecutorMode {
    VIRTUAL_THREAD_PER_TASK,
    PLATFORM_THREAD_POOL,
    SHARED_EXECUTOR
}
```

文档提到：

> 若使用 Spring 注入的共享 executor，scope 只能取消任务，不能关闭共享 executor。

**问题：**

这是正确的，但 `DefaultTaskScope.close()` 的行为需要区分：

- `VIRTUAL_THREAD_PER_TASK` / `PLATFORM_THREAD_POOL` → close 时 shutdown executor
- `SHARED_EXECUTOR` → close 时只取消任务，不 shutdown

如果 close 的实现统一调用 `executor.shutdown()`，共享 executor 会被意外关闭。

**建议：**

在 `ScopeOptions` 或 `DefaultTaskScope` 中记录 executor 的所有权：

```java
// close() 中
if (executorOwnedByScope) {
    executor.shutdown();
}
// 否则只取消任务，不关闭 executor
```

### 3.10 [LOW] 可观测性缺少作用域级视图

**原设计：**

`ObservedCallable` 记录单个任务的指标（名称、状态、耗时、异常），但缺少作用域级的汇总：

- 一个 scope 内有多少任务？成功/失败/取消各多少？
- scope 总耗时是多少？
- 哪个任务最慢？

**建议：**

在 `DefaultTaskScope` 中增加作用域级日志：

```java
// close() 中
log.debug("TaskScope '{}' completed: total={}ms, success={}, failed={}, cancelled={}",
    name, scopeElapsed, successCount, failedCount, cancelledCount);
```

## 4. JEP 演进中值得参考的设计决策

以下是从 JEP 505 → 525 → 533 演进中提炼的、被反复验证的设计决策。原设计不一定需要采纳，但值得作为参考：

### 4.1 Joiner 模式 — 策略与结果收集一体化

JDK 选择让 `Joiner` 同时承担"策略判断"和"结果收集"两个职责：

```java
public interface Joiner<T, R, R_X extends Throwable> {
    boolean onFork(Subtask<T> subtask);
    boolean onComplete(Subtask<T> subtask);
    R result() throws R_X;
    R timeout() throws R_X;
}
```

原设计将两者分离：`ScopePolicyHandler` 管策略，`Subtask.result()` 管结果。分离的好处是职责清晰，缺点是无法表达"策略决定结果类型"（如 `anySuccessfulOrThrow()` 直接返回成功结果，而不是返回 Subtask 列表）。

**评估：** 对于当前项目场景（RAG 检索、模型刷新、沙箱执行），分离方案足够。如果未来需要"竞速返回第一个成功结果"的语义，可考虑增加 `ScopeResult` 抽象。

### 4.2 结构化强制 — owner 线程约束

JEP 505/525/533 均强制：只有创建 scope 的线程才能 fork/join/close。这是"代码结构决定并发结构"原则的运行时保障。

**评估：** 建议采纳。这是结构化并发的核心约束之一，放弃它会失去"代码块边界 = 任务边界"的保证。

### 4.3 超时作为策略的一部分

JEP 533 将超时行为从 scope 配置移到 Joiner 的 `timeout()` 方法，让策略决定超时后返回什么。

**评估：** 与 3.3 节建议一致 — `ScopePolicyHandler` 应有 `onTimeout()` 回调。

## 5. 总结

原设计在"抽取结构化并发有价值语义"这一目标下，核心框架设计合理。六个声明语义中有四个覆盖完整，两个（失败传播、可观测性）存在需要修补的漏洞。

需要修正的问题按优先级：

| 优先级 | 问题 | 修正方向 |
|--------|------|----------|
| HIGH | COLLECT_ALL 失败静默 | close() 增加安全网日志 |
| HIGH | 超时语义不完整 | 明确 joinUntil / defaultTimeout 优先级 |
| HIGH | ScopePolicyHandler 缺少超时回调 | 增加 onTimeout() |
| MEDIUM | Subtask.result() 契约不明确 | 补充 JavaDoc 注释 |
| MEDIUM | SHUTDOWN_ON_SUCCESS 全部失败未定义 | 补充语义表 |
| MEDIUM | 缺少 owner 线程约束声明 | 明确选择并记录 |
| MEDIUM | 异常聚合缺少 allFailures() | 增加访问方法 |
| LOW | fork 缺少 Runnable 重载 | 增加 default 方法 |
| LOW | SHARED_EXECUTOR 生命周期 | 区分 executor 所有权 |
| LOW | 可观测性缺少 scope 级视图 | close() 增加汇总日志 |

这些问题不影响整体设计方向，修正后即可进入实现阶段。

---

## 参考资料

| 资源 | 说明 |
|------|------|
| [JEP 505](https://openjdk.org/jeps/505) | JDK 25 结构化并发第五次预览 — Joiner 接口、Configuration、ScopedValue 继承 |
| [JEP 525](https://openjdk.org/jeps/525) | JDK 26 第六次预览 — onTimeout()、List 返回值、UnaryOperator 配置 |
| [JEP 533](https://openjdk.org/jeps/533) | JDK 27 第七次预览 — 类型化异常 R_X、timeout() 替代 onTimeout()、awaitAll 移除 |
| [SoftwareMill 批评](https://softwaremill.com/critique-of-jep-505-structured-concurrency-fifth-preview/) | 非均匀取消、Subtask.get() 混淆、timeout 配置争议 |
| [Bazlur Rahman 分析](https://bazlur.ca/2026/01/04/structured-concurrency-in-java-26-api-polishing-timeouts-and-better-joiners/) | JEP 525 实际使用体验 |

---

## 6. 实现可行性与正确性审查（第二轮）

> 审查维度：**"照这个文档写代码，会卡在什么地方？"**
>
> 前一轮审查（第 3 节）侧重语义覆盖度和 JEP 演进对比。原设计已吸收修复。本轮聚焦**实现路径**——抽象层合理不等于能顺利编码。
>
> 审查日期：2026-05-31

### 6.1 [P0] `join()` 的核心等待机制未指定

这是最大的实现盲区。设计描述了 `join()` 的语义（等待所有任务完成或策略触发停止），但**没有说明如何高效地等待 N 个 `Future`**。

可选方案及其问题：

| 方案 | 问题 |
|------|------|
| 逐个 `Future.get(remaining)` | 串行等待，N 个任务实际等待时间 = sum，不是 max |
| `CompletableFuture.allOf().get(timeout)` | 可以等待全部，但**无法在第一个失败时提前退出**（`SHUTDOWN_ON_FAILURE` 需要） |
| `CountDownLatch` + 轮询 | latch 到 0 后无法区分"第几个完成了"；轮询有延迟 |
| `Phaser` | 每次到达触发 `onAdvance`，可以在回调里执行策略 — 但回调在任务线程执行，需要线程安全 |

推荐方案：每个 `SubtaskInternal` 持有一个 `CompletableFuture<Void>` 作为完成信号（completion signal）。`join()` 用 `CompletableFuture.anyOf()` 检测任意完成，然后检查策略：

```java
void joinUntil(Duration timeout) {
    long deadline = nanoTime() + timeout.toNanos();
    while (true) {
        if (state.allTerminal()) break;
        if (policyHandler.shouldStop(state)) { cancelUnfinished(); break; }

        long remaining = deadline - nanoTime();
        if (remaining <= 0) { policyHandler.onTimeout(state); break; }

        // 关键：等待任意一个任务完成
        CompletableFuture.anyOf(completionFutures())
            .get(remaining, NANOSECONDS);
    }
}
```

这意味着 `Subtask` 内部依赖 `CompletableFuture`，而设计文档完全没有提到这一点。实现者会面临"用不用 CF"的决策犹豫。

**建议**：在设计文档中增加"实现约束"一节，明确 join 的内部等待机制。

### 6.2 [P0] 首个迁移场景的策略不匹配

设计文档推荐 `HybridSearchService` 作为首个迁移目标，并建议默认使用 `SHUTDOWN_ON_FAILURE`。但**实际代码的语义是"优雅降级"**，不是"失败即取消"：

```java
// HybridSearchService.hybridSearch() 当前实现
vectorFuture.exceptionally(ex -> {
    vectorFailed.set(true);
    log.warn("Vector search degraded: {}", ex.getMessage());
    return Collections.emptyList();  // 失败后返回空列表，不取消 BM25
})
.thenCombine(bm25Future.exceptionally(ex -> {
    bm25Failed.set(true);
    log.warn("BM25 search degraded: {}", ex.getMessage());
    return Collections.emptyList();  // 失败后返回空列表，不取消 vector
}), (vec, bm25) -> rrfFusion(vec, bm25))
```

如果用 `SHUTDOWN_ON_FAILURE` 迁移，一个检索分支失败会立即取消另一个分支——**行为从"降级"变成"单点故障"**，这是回归，不是改进。

`COLLECT_ALL` 更接近当前语义，但 `COLLECT_ALL` 不做融合——需要调用方自己处理部分结果。实际需求是自定义策略："收集全部结果，如果全部失败才抛异常，否则用部分结果继续"。

**建议**：设计文档的迁移分析（第 17 节）应按实际代码行为重新评估每个场景的匹配策略，而不是假设 `SHUTDOWN_ON_FAILURE` 是通用默认。

### 6.3 [P0] `ScopeOptions` 作为 record 无法支持渐进配置

`ScopeOptions` 有 9 个字段。Java record 要求构造时提供全部值：

```java
// 调用方只想改 timeout，但必须填写全部 9 个参数
new ScopeOptions(
    "my-scope",
    ScopePolicy.SHUTDOWN_ON_FAILURE,
    ExecutorMode.VIRTUAL_THREAD_PER_TASK,
    0,                          // maxConcurrency — 不需要
    Duration.ofSeconds(5),      // timeout — 想改这个
    true,                       // executorOwnedByScope
    true,                       // inheritMdc
    false,                      // inheritSecurityContext — 不需要
    false                       // inheritRequestContext — 不需要
);
```

Java record 没有 `withXxx()` 方法。设计文档只提供了一个静态工厂 `shutdownOnFailure(name)`，但无法从该预设修改单个字段。

**建议**：提供 Builder 模式：

```java
ScopeOptions.builder("my-scope")
    .policy(ScopePolicy.SHUTDOWN_ON_FAILURE)
    .defaultTimeout(Duration.ofSeconds(5))
    .build();
```

### 6.4 [P1] `fork()` 在 `join()` 之后是否允许未定义

设计文档说"fork() 只能在 scope 未关闭时调用"，但未说明**是否允许在 `join()` 之后、`close()` 之前继续 `fork()`**：

```java
try (TaskScope scope = scopedTasks.open("test")) {
    scope.fork("a", this::work);
    scope.join();

    // 这里还能 fork 吗？
    scope.fork("b", this::moreWork);
    scope.join();
}
```

JDK preview API **禁止**在 `join()` 后 `fork()`（必须 close 重新 open）。如果本设计允许，`join()` 的第二次调用需要只等待新任务；如果不允许，需要在代码中强制检查。

**建议**：明确选择一种语义并在设计中记录。推荐与 JDK 一致——禁止 join 后 fork。

### 6.5 [P1] `close()` 等待终止缺少超时上限

设计文档承认 `close()` 必须等待任务终止，但说"等待终止的最大时间可以复用剩余 timeout，或由 ScopeOptions.closeTimeout 在后续阶段补充"。这是将最关键的实现决策推迟到实现阶段。

问题场景：一个子任务捕获了 `InterruptedException` 但没有退出（坏任务），`close()` 会永远阻塞在 `Future.get()` 上。

**建议**：第一阶段就必须有 `closeTimeout`。用 `ScopeOptions.defaultTimeout` 的 2 倍或固定 5 秒作为兜底：

```java
Duration closeTimeout = options.closeTimeout() != null
    ? options.closeTimeout()
    : options.defaultTimeout().multipliedBy(2);
```

### 6.6 [P1] 策略处理器和 ScopeState 的线程安全模型未定义

策略处理器的 `onSuccess` / `onFailure` 何时被调用？

- **选项 A**：在 `join()` 的 owner 线程轮询时调用 → 策略处理器不需要线程安全
- **选项 B**：在任务完成线程的回调中调用 → 策略处理器必须线程安全

设计文档的 8.2 节暗示是 owner 线程，但没有明确声明。如果实现者误解为在任务线程中回调，会引入竞态条件。

`ScopeState` 也有同样问题：`ObservedCallable` 在任务线程中 `markSuccess/markFailed`，而 `join()` 在 owner 线程读状态。`ScopeState` **必须线程安全**。

**建议**：在设计中增加一节"线程模型"，明确：

| 操作 | 执行线程 | 同步要求 |
|------|----------|----------|
| `fork/join/close` | owner 线程 | 无（单线程访问） |
| `SubtaskInternal.markSuccess/markFailed` | 任务线程 | 需要 volatile/synchronized |
| `ScopePolicyHandler` 回调 | owner 线程 | 不需要线程安全 |

### 6.7 [P1] 虚拟线程 + `synchronized` 导致 carrier pinning

JDK 21 已知问题：虚拟线程在 `synchronized` 块内执行 IO 操作会**固定住 carrier 线程**。如果 `ScopeState` 使用 `synchronized` 保护，在高并发下会导致 carrier 线程耗尽。

设计中 `MdcContextCarrier` 和 `DefaultTaskScope` 的内部状态管理如果用 `synchronized`，都会触发此问题。

**建议**：设计中应明确要求使用 `ReentrantLock` 而非 `synchronized`，或者使用 `AtomicReference` / `ConcurrentLinkedQueue` 等无锁结构。

### 6.8 [P2] `Subtask.result()` 异常类型过多，缺少公共父类

`result()` 在三种非成功状态下抛三种不同异常：`SubtaskNotCompletedException` / `SubtaskFailedException` / `SubtaskCancelledException`。调用方通常只关心"成功 or 失败"，不关心具体的失败类型：

```java
try {
    return subtask.result();
} catch (SubtaskNotCompletedException | SubtaskFailedException | SubtaskCancelledException e) {
    // 三种异常，处理方式通常一样
}
```

**建议**：用公共父类 `SubtaskException` 统一 catch：

```java
public abstract class SubtaskException extends RuntimeException { ... }
public final class SubtaskFailedException extends SubtaskException { ... }
public final class SubtaskCancelledException extends SubtaskException { ... }
public final class SubtaskNotCompletedException extends SubtaskException { ... }
```

### 6.9 [P2] `ContextCarrier<S>` 泛型擦除增加注册复杂度

每个 carrier 有不同的 `S` 类型（MDC 是 `Map<String, String>`，Security 可能是 `SecurityContext`）。`ScopedTasks` 需要维护 `List<ContextCarrier<?>>`，但注册时类型擦除：

```java
// 调用方
scopedTasks.registerCarrier(mdcCarrier);     // ContextCarrier<Map<String, String>>
scopedTasks.registerCarrier(securityCarrier); // ContextCarrier<SecurityContext>
// 内部变成 List<ContextCarrier<?>> — 通配符丢失了类型信息
```

这在实现上可行（`ContextSnapshot.capture` 内部做 unchecked cast），但设计文档应承认这个类型擦除，避免实现者困惑。

### 6.10 [P2] 嵌套 scope 的中断传播未确认

如果子任务内部也开了 scope：

```java
try (TaskScope parent = scopedTasks.open("parent")) {
    parent.fork("child", () -> {
        try (TaskScope inner = scopedTasks.open("inner")) {
            inner.fork("work", this::slowWork);
            inner.join();
        }
    });
    parent.joinUntil(Duration.ofSeconds(1));
}
```

当 parent 超时取消 "child" 任务时，child 线程收到中断。如果 child 正阻塞在 `inner.join()`，中断应传播到 inner scope，inner 应取消 "work" 并关闭。

这个行为**依赖于 `join()` 的实现方式正确传播中断**。如果 `join()` 用 `CompletableFuture.get()` 的超时变体，它抛 `ExecutionException` 而不是 `InterruptedException`，嵌套 scope 的取消链会断裂。

**建议**：设计中确认嵌套 scope 是支持的场景，并要求 `join()` 实现必须正确传播 `InterruptedException`。

### 6.11 第二轮审查总结

| 级别 | 问题 | 影响 |
|------|------|------|
| **P0** | join 的等待机制未指定 | 实现者会在 CF vs CountDownLatch vs Phaser 之间犹豫；选错方案会导致正确性问题 |
| **P0** | 首个迁移场景的策略不匹配 | HybridSearchService 实际是降级语义，不是 SHUTDOWN_ON_FAILURE |
| **P0** | ScopeOptions record 无法渐进配置 | 9 字段 record 没有 builder，调用方必须记住所有参数顺序 |
| **P1** | fork-after-join 未定义 | 实现者自行决定，可能导致不同实现行为不一致 |
| **P1** | close() 无超时上限 | 坏任务导致 close() 永远阻塞 |
| **P1** | 策略处理器和 ScopeState 的线程安全未声明 | 实现者可能忘记同步，导致竞态条件 |
| **P1** | synchronized 在虚拟线程上导致 carrier pinning | 高并发下性能退化 |
| **P2** | result() 异常类型过多 | 调用方 catch 负担 |
| **P2** | ContextCarrier 类型擦除 | 注册时泛型丢失，实现需要 unchecked cast |
| **P2** | 嵌套 scope 中断传播未确认 | join 实现方式决定中断链是否完整 |

**核心结论**：设计的抽象层（TaskScope / Subtask / ScopePolicy）是合理的，但在"如何从 JDK 21 稳定 API 构建这些抽象"这一实现路径上，有 3 个阻碍项和 4 个正确性风险需要先解决。建议在开始编码前补充一节"实现约束"，明确 join 的内部机制、线程模型、close 超时策略。
