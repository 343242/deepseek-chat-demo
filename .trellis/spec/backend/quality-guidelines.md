# Quality Guidelines

> Code standards, forbidden patterns, and review criteria.

---

## Code Style

- **Java 21**：使用 record 定义 DTO，使用 var 局部变量类型推断
- **Spring Boot 3.5**：Jakarta EE 命名空间（`jakarta.*`）
- **编码**：UTF-8，LF 换行
- **缩进**：4 空格

---

## Design Principles — 面向对象设计七大原则

### SOLID（五大原则）

| 原则 | 缩写 | 定义 | 项目实践 |
|------|------|------|----------|
| **单一职责** | SRP | 一个类只有一个引起它变化的原因 | 每个类只做一件事；`OrphanChunkCleaner` 只管分片清理，`TeamBucketCleaner` 只管团队桶生命周期 |
| **开闭原则** | OCP | 对扩展开放，对修改关闭 | 新功能 = 新增类，不是改旧类；`BucketResolver` 新增场景只需扩展 `resolve()`，上传策略零改动 |
| **里氏替换** | LSP | 子类必须能替换其基类而不破坏正确性 | 接口实现必须遵守契约；`PersonalUploadStrategy` 和 `TeamUploadStrategy` 都实现 `UploadStrategy`，调用方无感知 |
| **接口隔离** | ISP | 客户端不应被迫依赖它不使用的接口 | 接口按职责拆分，不造大而全的接口；`TeamStatusService` 只暴露 `isTeamActive` / `isTeamMember`，不暴露团队 CRUD |
| **依赖倒置** | DIP | 高层模块不依赖低层模块，两者都依赖抽象 | `ChatService` 依赖 `ChatClientFactory` 接口而非具体实现；`ChunkUploadServiceImpl` 依赖 `TeamStatusService` 接口而非 `TeamMapper` |

### 补充原则

| 原则 | 缩写 | 定义 | 项目实践 |
|------|------|------|----------|
| **迪米特法则** | LoD | 一个对象应该对其他对象保持最少的了解 | Service 不直接操作其他模块的 Mapper；`rag` 模块需要团队状态时通过 `TeamStatusService` 接口查询，不直接注入 `TeamMapper` |
| **合成复用** | CRP | 优先使用对象组合而非继承来复用功能 | 用组合+委托替代继承；`TeamUploadStrategy` 组合 `DocumentValidator`、`FileStorageService`、`BucketResolver`，而非继承某个基类 |

### 强制规则

| 规则 | 说明 |
|------|------|
| **设计模式优先** | 策略、工厂、模板方法等主动运用，不能硬编码 |
| **OCP 强制** | 新功能 = 新增类，不是改旧类；加 Provider 不改 ChatService |
| **封装彻底** | 厂商差异、技术细节不泄漏到上层 |
| **DTO 隔离** | Entity 不暴露给前端，通过 DTO 转换 |
| **编程式事务** | `TransactionTemplate`，不用 `@Transactional` |
| **新增功能 Checklist** | PRD 必须验证"新增同类功能的步骤"确保零修改现有文件 |
| **批判式思考** | 每次编码前审视：设计模式、SOLID、OOP、可读性、可维护性 |

---

## Security Checklist

每次改动涉及以下内容时必须检查：

- [ ] **密码**：BCrypt 哈希，不存明文
- [ ] **Token**：HttpOnly Cookie 存储，不在 JSON body 返回
- [ ] **权限**：`@PreAuthorize` 注解保护接口
- [ ] **输入校验**：`@Valid` + Jakarta Validation
- [ ] **状态枚举**：用枚举类约束，不接受裸 Integer
- [ ] **唯一约束**：业务层先查重 + 数据库 partial unique index 兜底
- [ ] **软删除**：查询条件必须包含 `deleted = 0`
- [ ] **错误消息**：认证失败不暴露具体原因

---

## Forbidden Patterns

| 模式 | 替代方案 | 原因 |
|------|---------|------|
| `new Thread(...)` | 线程池（`ThreadPoolExecutor`） | 线程资源必须池化，禁止显式创建线程 |
| `Executors.newXxx()`（池化类） | `ThreadPoolExecutor` 显式构造 | 避免无界队列 OOM、线程数失控 |
| 类锁 / 锁整个方法体 | 最小区块锁 + 对象锁 | 高并发下减少锁竞争范围 |
| `@Transactional` | `TransactionTemplate` | 精确控制事务边界 |
| JPA / Hibernate | MyBatis-Plus | 项目已全量替换 |
| `IllegalArgumentException` | `ClientException` | 统一异常处理，见 [Error Handling](./error-handling.md) |
| `System.out.println` | SLF4J Logger | 日志框架 |
| 裸 Integer 状态字段 | 枚举类 + 校验 | 防止无效值 |
| Controller 内 try-catch | GlobalExceptionHandler | 统一错误格式 |
| 返回 Entity 给前端 | DTO 转换 | 隔离内部结构 |
| Token 放 JSON body | HttpOnly Cookie | 安全性 |
| Flyway | ~~已移除~~ → **已重新引入** | V4+ 迁移通过 Flyway 管理 |
| `docker pull *:alpine` | `*:bookworm` | 项目规则 |
| 不经允许拉 Docker 镜像 | 先问用户 | 项目规则 |

### `Executors` 禁令的豁免（virtual thread per task）

`Executors.newFixedThreadPool` / `newCachedThreadPool` / `newSingleThreadExecutor` / `newScheduledThreadPool` 等池化工厂方法**仍然禁用**（无界队列 OOM、线程数失控风险）。

**允许使用**以下两类——它们不构成池化（每次 `execute` 创建新 virtual thread，无 worker 复用、无任务队列累积）：

- `Executors.newVirtualThreadPerTaskExecutor()`
- `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())`

平台线程相关工厂方法**仍然禁用**：

- `Executors.defaultThreadFactory()` → 改用 `Thread.ofPlatform().factory()` 或自定义 `ThreadFactory`
- `Executors.privilegedThreadFactory()` → 同上

依据：virtual thread 的创建/调度开销是纳秒级，由 JVM 内部 carrier thread 调度，"每任务一 thread"是 JDK 21+ 设计意图。详见 [Concurrency Rules](#concurrency-rules)。

---

## DTO Rules

- Request DTO：用 `record`，加 `@Valid` 注解
- Response DTO：用 `record`，不加敏感信息（如 permissions 列表）
- 字段校验：`@NotBlank`、`@Email`、`@Size`、`@Pattern`
- email 统一 `toLowerCase` 处理

---

## Concurrency Rules

### 线程资源

- **线程必须通过线程池提供**，禁止在应用代码中显式 `new Thread()`
- **线程池必须通过 `ThreadPoolExecutor` 构造**，显式指定核心线程数、最大线程数、存活时间、工作队列、拒绝策略；**禁止使用 `Executors` 工厂方法**（避免无界队列 OOM 和线程数失控）

### 锁与同步

高并发场景下同步调用的锁性能损耗须纳入考量，遵循以下优先级：

| 优先级 | 策略 | 说明 |
|--------|------|------|
| 1（最优） | 无锁数据结构 | 能用无锁数据结构（`ConcurrentHashMap`、`AtomicLong`、`LongAdder` 等），就不要用锁 |
| 2 | 锁区块 | 能锁区块（`synchronized` 块 / `ReentrantLock.lock-unlock`），就不要锁整个方法体 |
| 3 | 对象锁 | 能用对象锁（实例级 `synchronized` / 实例级 `Lock`），就不要用类锁（`static synchronized` / `Class` 级锁） |

### Wrong vs Correct

```java
// Wrong — Executors 创建线程池，队列无界，OOM 风险
ExecutorService pool = Executors.newFixedThreadPool(10);

// Correct — ThreadPoolExecutor 显式指定所有参数
ExecutorService pool = new ThreadPoolExecutor(
    4, 8, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(256),
    new ThreadPoolExecutor.CallerRunsPolicy());

// Wrong — 锁整个方法体 + 类锁
public synchronized static void process() { ... }

// Correct — 锁最小区块 + 对象锁
private final Object lock = new Object();
public void process() {
    synchronized (lock) {
        // only the critical section
    }
}
```

---

## Scenario: Structured Request-Scoped Concurrency

> 使用 `com.smart.rag.common.concurrent` 的 `ScopedTasks` / `TaskScope`，不要把 `CompletableFuture` 直接暴露给业务层。

### 策略速查

| 策略 | 语义 | 典型场景 |
|------|------|---------|
| `SHUTDOWN_ON_FAILURE` | 任一失败即取消剩余 | 必须全部成功的并行 IO |
| `COLLECT_ALL` | 等待全部完成，逐个检查结果 | partial-success 检索、Provider 刷新 |
| `SHUTDOWN_ON_SUCCESS` | 首个成功即取消剩余 | 竞速/镜像源 |
| `PARTIAL_SUCCESS_OR_THROW` | 至少一个成功即可 | 降级容忍型聚合 |
| `QUORUM_SUCCESS` | 达到 N 个成功即取消 | 多候选仲裁 |

### 核心契约

- `TaskScope` 必须用 `try-with-resources`；`fork`/`join`/`close` 只能由 owner 线程调用
- `join` 只能调用一次，之后禁止 `fork`
- `Subtask.result()` 非阻塞，`join` 后读取；未完成/失败/取消分别抛对应异常
- `COLLECT_ALL` 不忽略失败 — 必须调 `throwIfFailed()` 或检查 `exception()`
- `inheritSecurityContext` / `inheritRequestContext` 默认关闭，按需显式开启
- `SHARED_EXECUTOR` 必须搭配 `executorOwnedByScope=false`，scope 不能关闭共享 executor
- 不要在同一个可能饱和的 executor 中 fork + join — 会死锁；owner 线程来自调用栈或另一个 executor
- **`defaultTimeout` 必须 > 0**（默认 `Duration.ofSeconds(30)`）— `ZERO` 不再表示"无限等待"，构造时拦截。需要"无限等待"时显式传 `ScopeOptions.NO_TIMEOUT`，并明确文档化理由（避免隐式死锁）
- **`Cleaner` 必须真正清理**，不能只 log warning——`ScopeCleanupState` 在 scope 泄漏（未 close 即被 GC）时必须：owned executor → `shutdownNow()` + cancel 所有未终止 subtask；SHARED executor → 只 cancel subtask + 警告（保护共享资源）
- **scope 关闭必须遵守 LIFO**——`scopeOpened()` 返回 `scopeId`，`scopeClosed(expectedScopeId)` 校验栈顶匹配，违例抛 `ScopeViolationException`

### 错误速查

| Condition | 异常 |
|-----------|------|
| 非 owner 调用生命周期方法 | `ScopeViolationException` |
| join/close 后继续 fork | `ScopeClosedException` |
| 任务失败 + fail-fast 策略 | `ScopeExecutionException`（含 `unacceptableFailures()`） |
| `joinUntil` 超时 | `ScopeTimeoutException` |
| `SHARED_EXECUTOR` + `executorOwnedByScope=true` | `ScopeViolationException` |

### 必要测试

- owner 约束、join 后禁 fork、close 幂等
- 每种策略的成功/失败/超时路径
- partial-success 链路：全成功、左失败右成功、左成功右失败、全失败
- MDC 子任务可见 + owner 线程不污染
- 共享 executor 关闭等待亚秒 `closeTimeout`，不关闭调用方 executor

### Wrong vs Correct

```java
// Wrong — 裸 CompletableFuture，无超时、无生命周期管理
CompletableFuture<A> a = CompletableFuture.supplyAsync(this::loadA);
CompletableFuture<B> b = CompletableFuture.supplyAsync(this::loadB);
return combine(a.join(), b.join());

// Correct — ScopedTasks 管理生命周期、超时和错误传播
try (TaskScope scope = scopedTasks.open(“load-and-combine”)) {
    Subtask<A> a = scope.fork(“load-a”, this::loadA);
    Subtask<B> b = scope.fork(“load-b”, this::loadB);

    scope.joinUntil(Duration.ofSeconds(3));
    scope.throwIfFailed();

    return combine(a.result(), b.result());
}
```

---

## Git Commit Rules

- 每次改动后必须 commit + push
- commit message 首行必须使用 Conventional Commits 风格：`type(scope): summary`
- `type` 用一个词说明改动类型，例如 `fix`、`feat`、`docs`、`test`、`refactor`、`chore`
- `scope` 必须写清楚涉及模块，多个模块用逗号分隔，例如 `fix(rag,chat): fix stale unit tests`
- `summary` 用简短英文说明本次提交做了什么；正文和 git trailers 可继续按 Lore Commit Protocol 补充约束、取舍和验证信息
